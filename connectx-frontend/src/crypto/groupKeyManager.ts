import { keyManager } from './keyManager';
import { deviceApi } from '../api/deviceApi';
import { groupApi } from '../api/groupApi';
import { cryptoStorage } from './storage';
import { encryptMessage } from './encryption';
import { decryptMessage } from './decryption';
import {
  generateGroupKey,
  exportGroupKeyRaw,
  importGroupKeyRaw,
} from './groupCrypto';
import { Group, GroupMemberKeyPayload } from '../types';

/**
 * The single GROUP key-management boundary (Part 20): current key, current version,
 * initialization, retrieval, local unwrap, rotation, missing-key state, and cleanup all live here.
 * Nothing outside this module reads group_member_keys wrapped rows or touches the group-key
 * IndexedDB store directly -- MessageInput/App.tsx/GroupContactInfoDrawer only ever call
 * ensureGroupKey/getKeyForVersion/cleanupGroup below, never duplicate this state themselves.
 *
 * Key lifecycle recap (server is authoritative for WHEN a rotation is required --
 * ChatGroup.keyVersion / Group.keyVersion -- this module only decides WHETHER this client needs to
 * do something about it):
 *  - If a locally cached key already matches the group's current authoritative version, use it.
 *    No network call.
 *  - Otherwise pull this member's own wrapped key from the server. If its version already matches
 *    (or exceeds, under a race), unwrap it (reusing the exact DIRECT ECDH primitives --
 *    encryptMessage/decryptMessage -- with the wrapper's public key) and cache it.
 *  - Otherwise (no row yet, or a strictly older version) THIS client self-elects as the rotator:
 *    mint a fresh random AES-256 key, wrap it for every currently active member (itself included),
 *    and submit each wrapped copy. A version re-check right before distributing avoids clobbering
 *    another client that already finished rotating in the meantime.
 */

interface CachedEntry {
  keyVersion: number;
  key: CryptoKey;
}

const memoryCache = new Map<number, CachedEntry>();
const inFlight = new Map<number, Promise<CryptoKey | null>>();

async function unwrapRow(currentUserId: number, row: GroupMemberKeyPayload): Promise<CryptoKey | null> {
  if (!row.wrappedByUserId) {
    return null;
  }
  const myPrivateKey = await keyManager.getPrivateKey(currentUserId);
  if (!myPrivateKey) {
    return null;
  }
  const wrapperKeys = await deviceApi.getUserPublicKeys(row.wrappedByUserId).catch(() => null);
  if (!wrapperKeys || wrapperKeys.length === 0) {
    return null;
  }
  try {
    const rawBase64 = await decryptMessage(myPrivateKey, wrapperKeys[0].publicKey, row.wrappedKey, row.wrapNonce);
    return await importGroupKeyRaw(rawBase64);
  } catch (err) {
    console.warn('[ConnectX Group E2EE] Failed to unwrap group key:', err);
    return null;
  }
}

async function distributeNewKey(groupId: number, currentUserId: number, key: CryptoKey, keyVersion: number): Promise<void> {
  const members = await groupApi.getGroupMembers(groupId);
  const rawBase64 = await exportGroupKeyRaw(key);
  const myPrivateKey = await keyManager.getPrivateKey(currentUserId);
  if (!myPrivateKey) {
    throw new Error('Local private key is missing; cannot wrap group key for distribution.');
  }

  await Promise.all(
    members.map(async (member) => {
      const targetUserId = member.user.id;
      const targetKeys = await deviceApi.getUserPublicKeys(targetUserId).catch(() => null);
      if (!targetKeys || targetKeys.length === 0) {
        // Best-effort: a member with no registered public key simply doesn't get a wrapped copy
        // yet (mirrors DIRECT's own "recipient has no registered public keys" handling) -- they'll
        // pick one up next time their own client calls ensureGroupKey.
        return;
      }
      try {
        const wrapped = await encryptMessage(myPrivateKey, targetKeys[0].publicKey, rawBase64);
        await groupApi.submitGroupKey(groupId, targetUserId, wrapped.ciphertext, wrapped.nonce, keyVersion);
      } catch (err) {
        console.warn(`[ConnectX Group E2EE] Failed to distribute group key to user ${targetUserId}:`, err);
      }
    })
  );
}

async function resolveInternal(group: Group, currentUserId: number, allowRotate: boolean): Promise<CryptoKey | null> {
  const groupId = group.id;
  const authoritativeVersion = group.keyVersion;

  const cached = memoryCache.get(groupId);
  if (cached && cached.keyVersion >= authoritativeVersion) {
    return cached.key;
  }

  const inFlightKey = allowRotate ? groupId : -groupId - 1; // separate slot so a passive caller never blocks on / gets blocked by an active rotation, or vice versa
  const existing = inFlight.get(inFlightKey);
  if (existing) {
    return existing;
  }

  const promise = (async (): Promise<CryptoKey | null> => {
    try {
      const cachedRaw = await cryptoStorage.getGroupKeyRaw(groupId, authoritativeVersion);
      if (cachedRaw) {
        const key = await importGroupKeyRaw(cachedRaw);
        memoryCache.set(groupId, { keyVersion: authoritativeVersion, key });
        return key;
      }

      const row = await groupApi.getMyGroupKey(groupId).catch(() => null);
      if (row && row.keyVersion >= authoritativeVersion) {
        const key = await unwrapRow(currentUserId, row);
        if (key) {
          const rawBase64 = await exportGroupKeyRaw(key);
          await cryptoStorage.saveGroupKeyRaw(groupId, row.keyVersion, rawBase64);
          memoryCache.set(groupId, { keyVersion: row.keyVersion, key });
          return key;
        }
        // A row exists at the right version but couldn't be unwrapped (e.g. the wrapper's
        // public key is unavailable) -- do NOT fall through to minting a replacement key here:
        // other members may already hold a valid key for this exact version, and distributing a
        // different one under the same version number would silently break decryption for them.
        return null;
      }

      if (!allowRotate) {
        return null;
      }

      // No usable row yet (never received one, or it's strictly behind the authoritative
      // version) -- this client self-elects as rotator. Only reached via ensureGroupKey
      // (allowRotate=true), called from a small, deterministic set of trigger points (see that
      // function's own doc) specifically to avoid many clients racing to mint different keys for
      // the same version -- confirmed as a real, observed failure mode via live multi-user
      // testing (concurrent self-election from every client's passive "group opened" check) before
      // this active/passive split existed.
      const newKey = await generateGroupKey();
      const latestGroup = await groupApi.getGroup(groupId).catch(() => group);
      if (latestGroup.keyVersion > authoritativeVersion) {
        // Someone else already advanced the version further while we were working --
        // restart against the newer authoritative state instead of distributing a stale one.
        return resolveInternal(latestGroup, currentUserId, true);
      }
      await distributeNewKey(groupId, currentUserId, newKey, latestGroup.keyVersion);
      const rawBase64 = await exportGroupKeyRaw(newKey);
      await cryptoStorage.saveGroupKeyRaw(groupId, latestGroup.keyVersion, rawBase64);
      memoryCache.set(groupId, { keyVersion: latestGroup.keyVersion, key: newKey });
      return newKey;
    } catch (err) {
      console.warn('[ConnectX Group E2EE] group key resolution failed:', err);
      return null;
    } finally {
      inFlight.delete(inFlightKey);
    }
  })();

  inFlight.set(inFlightKey, promise);
  return promise;
}

export const groupKeyManager = {
  /**
   * Resolves the group's CURRENT shared key, minting and distributing a brand-new one if this
   * client can't find any usable row (self-electing as rotator). Reserved for a small,
   * deterministic set of call sites where exactly one client is expected to be the one doing this
   * -- the member who just accepted an invitation, the actor who just removed/left a member, or a
   * composer about to send (the last-resort case Part 6 describes: nobody else was positioned to
   * rotate, so whoever needs to send next does). Do NOT call this from a passive
   * "group is open/a message arrived" check -- use resolveGroupKey for that, which never mints.
   * Calling this from many places at once is exactly how multiple clients each mint a DIFFERENT
   * key for the same version, observed live before this split existed (see resolveInternal).
   */
  async ensureGroupKey(group: Group, currentUserId: number): Promise<CryptoKey | null> {
    if (!group) {
      return null;
    }
    return resolveInternal(group, currentUserId, true);
  },

  /**
   * Passive-only resolution: cache -> own wrapped row -> unwrap. Never mints or distributes a new
   * key. Use this for anything that isn't one of the deterministic rotation trigger points above
   * (decrypting an incoming/loaded message, proactively warming the key when a group is opened to
   * read). Returns null if no key is available yet -- callers should treat that as "not ready",
   * not as license to become a rotator themselves.
   */
  async resolveGroupKey(group: Group, currentUserId: number): Promise<CryptoKey | null> {
    if (!group) {
      return null;
    }
    return resolveInternal(group, currentUserId, false);
  },

  /** Synchronous best-effort lookup for a specific (possibly historical) version -- used when
   * decrypting an already-loaded message list without awaiting IndexedDB for every row. */
  getCachedKeySync(groupId: number, keyVersion: number): CryptoKey | null {
    const cached = memoryCache.get(groupId);
    return cached && cached.keyVersion === keyVersion ? cached.key : null;
  },

  /** Resolves the key for a SPECIFIC (possibly historical) version, for decrypting an existing
   * message -- never mints or distributes a new key. Returns null if this client never held that
   * version's key (expected for a member who joined after that version was rotated away). */
  async getKeyForVersion(groupId: number, keyVersion: number): Promise<CryptoKey | null> {
    const cached = memoryCache.get(groupId);
    if (cached && cached.keyVersion === keyVersion) {
      return cached.key;
    }
    const rawBase64 = await cryptoStorage.getGroupKeyRaw(groupId, keyVersion);
    if (!rawBase64) {
      return null;
    }
    const key = await importGroupKeyRaw(rawBase64);
    if (!cached || keyVersion >= cached.keyVersion) {
      memoryCache.set(groupId, { keyVersion, key });
    }
    return key;
  },

  /** Drops the in-memory cache entry so the next ensureGroupKey call re-checks the server rather
   * than trusting a version that might now be stale (e.g. after a GROUP_KEY_ROTATION_REQUIRED
   * notice). Does not touch the IndexedDB cache -- historical versions stay available for
   * decrypting older messages. */
  invalidate(groupId: number): void {
    memoryCache.delete(groupId);
  },

  /** Full local cleanup when the group is deleted or the current user leaves/is removed -- no
   * cached key material for that group should remain in this browser. */
  async cleanupGroup(groupId: number): Promise<void> {
    memoryCache.delete(groupId);
    inFlight.delete(groupId);
    await cryptoStorage.clearGroupKeys(groupId);
  },
};
