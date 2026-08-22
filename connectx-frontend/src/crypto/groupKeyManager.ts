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

// Phase 7C: resolveInternal now returns the ACTUAL (key, keyVersion) pair it resolved, not just
// the bare key. This matters specifically for the allowRotate=true (send-path) caller: if the
// last-resort mint fallback below claims a genuinely new version via rotateGroupKeyForRecovery,
// that version can be higher than the `group.keyVersion` the caller's own closure still holds --
// a real live-tested bug (Phase 7C) had MessageInput tag outgoing messages with the STALE closure
// version while encrypting with the NEWLY minted key, corrupting decryption for every recipient
// who correctly looked up the (different) real key for that version number. Callers that only
// need the key (passive decrypt paths) can keep unwrapping `.key`; MessageInput's send paths now
// use both `.key` and `.keyVersion` from this result instead of `group.keyVersion`.
interface ResolvedKey {
  key: CryptoKey;
  keyVersion: number;
}

const memoryCache = new Map<number, CachedEntry>();
const inFlight = new Map<number, Promise<ResolvedKey | null>>();

// Phase 7B key reconciliation: when a passive resolution finds a genuine gap (no row yet, or a
// stale one, at the CURRENT authoritative version) it must recover the EXISTING key from another
// member who already holds it, never mint a replacement just because this client is missing one
// (see resolveInternal's allowRotate=false branches below, and groupApi.requestGroupKey's own
// doc). Both maps below are in-memory/per-tab only -- bounded by construction (a fixed retry
// count and a cooldown window), so losing them on reload just means the next natural trigger
// (an incoming message, a reconnect, opening the group) starts a fresh, harmless attempt.
const RECONCILE_REQUEST_THROTTLE_MS = 15_000;
const RECONCILE_RETRY_DELAYS_MS = [3_000, 8_000, 15_000]; // bounded: 3 attempts, then give up until the next natural trigger
const lastReconcileRequestAt = new Map<number, number>();
const reconcileRetryTimers = new Map<number, ReturnType<typeof setTimeout>>();

/** Fire-and-forget, throttled ask to every OTHER active member: "please re-wrap the group's
 * CURRENT key for me." Never rotates -- ChatGroup.keyVersion is untouched by this call. Safe to
 * call repeatedly; the cooldown keeps a burst of passive-resolution misses (e.g. several messages
 * arriving in a row while the key is still missing) from spamming every other member's queue with
 * duplicate requests. */
function requestReconciliation(groupId: number): void {
  const now = Date.now();
  const last = lastReconcileRequestAt.get(groupId) ?? 0;
  if (now - last < RECONCILE_REQUEST_THROTTLE_MS) {
    return;
  }
  lastReconcileRequestAt.set(groupId, now);
  groupApi.requestGroupKey(groupId).catch((err) => {
    console.warn(`[ConnectX Group E2EE] Failed to request key reconciliation for group ${groupId}:`, err);
  });
}

/** Bounded, self-terminating re-check chain: after asking another member to re-wrap, briefly
 * re-attempts the same passive resolution a few times (in case the fulfiller's response lands a
 * few seconds later) before giving up until the next natural trigger notices the key arrived.
 * Never escalates to allowRotate=true. At most one retry chain per group at a time. */
function scheduleBoundedRetry(group: Group, currentUserId: number, attempt: number): void {
  if (attempt >= RECONCILE_RETRY_DELAYS_MS.length || reconcileRetryTimers.has(group.id)) {
    return;
  }
  const timer = setTimeout(() => {
    reconcileRetryTimers.delete(group.id);
    resolveInternal(group, currentUserId, false)
      .then((resolved) => {
        if (!resolved) {
          scheduleBoundedRetry(group, currentUserId, attempt + 1);
        }
      })
      .catch(() => {});
  }, RECONCILE_RETRY_DELAYS_MS[attempt]);
  reconcileRetryTimers.set(group.id, timer);
}

function cancelBoundedRetry(groupId: number): void {
  const timer = reconcileRetryTimers.get(groupId);
  if (timer) {
    clearTimeout(timer);
    reconcileRetryTimers.delete(groupId);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Bounded wait budget for the allowRotate=true (last-resort send) path only: two short waits for
// another member's fulfillment to land before this client gives up and mints a replacement. Kept
// short since a message send is blocked on this; the unbounded/background recovery for passive
// callers uses RECONCILE_RETRY_DELAYS_MS instead.
const RECONCILE_REQUEST_WAIT_DELAYS_MS = [1_500, 3_000];

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

async function resolveInternal(group: Group, currentUserId: number, allowRotate: boolean): Promise<ResolvedKey | null> {
  const groupId = group.id;
  const authoritativeVersion = group.keyVersion;

  const cached = memoryCache.get(groupId);
  if (cached && cached.keyVersion >= authoritativeVersion) {
    return { key: cached.key, keyVersion: cached.keyVersion };
  }

  const inFlightKey = allowRotate ? groupId : -groupId - 1; // separate slot so a passive caller never blocks on / gets blocked by an active rotation, or vice versa
  const existing = inFlight.get(inFlightKey);
  if (existing) {
    return existing;
  }

  const promise = (async (): Promise<ResolvedKey | null> => {
    try {
      const cachedRaw = await cryptoStorage.getGroupKeyRaw(groupId, authoritativeVersion);
      if (cachedRaw) {
        const key = await importGroupKeyRaw(cachedRaw);
        memoryCache.set(groupId, { keyVersion: authoritativeVersion, key });
        cancelBoundedRetry(groupId);
        return { key, keyVersion: authoritativeVersion };
      }

      const row = await groupApi.getMyGroupKey(groupId).catch(() => null);
      if (row && row.keyVersion >= authoritativeVersion) {
        const key = await unwrapRow(currentUserId, row);
        if (key) {
          const rawBase64 = await exportGroupKeyRaw(key);
          await cryptoStorage.saveGroupKeyRaw(groupId, row.keyVersion, rawBase64);
          memoryCache.set(groupId, { keyVersion: row.keyVersion, key });
          cancelBoundedRetry(groupId);
          return { key, keyVersion: row.keyVersion };
        }
        // A row exists at the right version but couldn't be unwrapped (e.g. the wrapper's
        // public key is unavailable) -- do NOT fall through to minting a replacement key here:
        // other members may already hold a valid key for this exact version, and distributing a
        // different one under the same version number would silently break decryption for them.
        // This may be transient (the wrapper's public key lookup blipped) or a genuine gap in
        // which case asking another holder to re-wrap is the same safe recovery path as the
        // "no row at all" case below -- harmless and idempotent either way (Phase 7B).
        requestReconciliation(groupId);
        if (!allowRotate) {
          scheduleBoundedRetry(group, currentUserId, 0);
        }
        return null;
      }

      if (!allowRotate) {
        // Phase 7B key reconciliation: no usable row exists yet for the CURRENT version. A
        // passive caller (decrypting an incoming/loaded message, warming the key on group-open)
        // must NEVER conclude "I should mint a new key" just because ITS OWN copy is missing --
        // that was the actual root cause of members intermittently being unable to decrypt GROUP
        // messages on a device/session that missed the original distribution (see this module's
        // top-level doc). Instead, ask an existing key holder to re-wrap the CURRENT key for this
        // user (never a version bump), and schedule a few bounded, self-terminating re-checks in
        // case the fulfillment lands a few seconds later. If nobody can fulfill it, this returns
        // null forever until the next natural trigger (an incoming message, a reconnect, opening
        // the group) tries again -- exactly the same "not ready yet" contract this already had.
        requestReconciliation(groupId);
        scheduleBoundedRetry(group, currentUserId, 0);
        return null;
      }

      // allowRotate=true (ensureGroupKey) reached here because no usable row existed. Per the
      // same Phase 7B rule, PREFER recovering the group's EXISTING current key over minting a
      // replacement, even from this last-resort send path -- ask, then wait briefly (bounded) for
      // another member's client to fulfill it before falling back to minting. This is the only
      // difference from resolveGroupKey's passive wait: the caller here (a blocked message send)
      // is already waiting on this promise, so the retry is synchronous and short rather than
      // scheduled in the background.
      requestReconciliation(groupId);
      for (const waitMs of RECONCILE_REQUEST_WAIT_DELAYS_MS) {
        await sleep(waitMs);
        const recovered = await resolveInternal(group, currentUserId, false);
        if (recovered) {
          return recovered;
        }
      }

      // Still nothing after bounded waiting for another member to fulfill the request -- this
      // client self-elects as rotator, truly as a last resort. Only reached via ensureGroupKey
      // (allowRotate=true), called from a small, deterministic set of trigger points (see that
      // function's own doc) specifically to avoid many clients racing to mint different keys for
      // the same version -- confirmed as a real, observed failure mode via live multi-user
      // testing (concurrent self-election from every client's passive "group opened" check) before
      // this active/passive split existed.
      //
      // Phase 7C fix: this must claim a genuinely NEW version via rotateGroupKeyForRecovery rather
      // than reusing `authoritativeVersion`/`group.keyVersion` as-is. Live multi-device testing
      // proved the old approach unsafe -- other members can already hold REAL, different key
      // material for that exact version (it may have been a legitimate rotation this client just
      // hasn't received a row for yet), and overwriting their group_member_keys rows with
      // different key bytes under the SAME version number doesn't just "waste" a version, it
      // silently corrupts decryption for every member who already cached the old value locally.
      const rotated = await groupApi.rotateGroupKeyForRecovery(groupId).catch((err) => {
        console.warn(`[ConnectX Group E2EE] Failed to claim a new key version for group ${groupId}:`, err);
        return null;
      });
      if (!rotated || rotated.keyVersion <= authoritativeVersion) {
        // Couldn't safely claim a new version -- do not guess, do not fall back to reusing the
        // old one. Caller sees this exactly like any other unresolved-key case.
        return null;
      }
      const newKey = await generateGroupKey();
      await distributeNewKey(groupId, currentUserId, newKey, rotated.keyVersion);
      const rawBase64 = await exportGroupKeyRaw(newKey);
      await cryptoStorage.saveGroupKeyRaw(groupId, rotated.keyVersion, rawBase64);
      memoryCache.set(groupId, { keyVersion: rotated.keyVersion, key: newKey });
      cancelBoundedRetry(groupId);
      return { key: newKey, keyVersion: rotated.keyVersion };
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
   * client can't find any usable row (self-electing as rotator) -- but only after Phase 7C's
   * reconciliation-first attempt (see resolveInternal). Reserved for a small, deterministic set of
   * call sites where exactly one client is expected to be the one doing this -- the member who
   * just accepted an invitation, the actor who just removed/left a member, or a composer about to
   * send (the last-resort case Part 6 describes: nobody else was positioned to rotate, so whoever
   * needs to send next does). Do NOT call this from a passive "group is open/a message arrived"
   * check -- use resolveGroupKey for that, which never mints.
   *
   * Returns BOTH the key and the keyVersion it was actually resolved/minted at -- callers that
   * tag outgoing data with a keyVersion (MessageInput's send paths) MUST use the returned
   * `keyVersion`, never a `group.keyVersion` read from their own closure/state, since this call
   * can resolve to a NEWER version than the caller knew about (a real live-tested corruption bug,
   * Phase 7C: encrypting with the correct new key but tagging the message with the stale version
   * number left every recipient decrypting with the wrong key).
   */
  async ensureGroupKey(group: Group, currentUserId: number): Promise<ResolvedKey | null> {
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
    const resolved = await resolveInternal(group, currentUserId, false);
    return resolved ? resolved.key : null;
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
    cancelBoundedRetry(groupId);
    lastReconcileRequestAt.delete(groupId);
    await cryptoStorage.clearGroupKeys(groupId);
  },

  /**
   * Phase 7B/7C key reconciliation: the FULFILLER side. Called when this client receives
   * GROUP_KEY_REWRAP_REQUESTED for a group whose current key it may hold. Resolves the current
   * key PASSIVELY ONLY -- if this client doesn't hold it either, there is nothing to fulfill, and
   * it must not mint one on the requester's behalf (that would be exactly the "gap causes a
   * rotation" failure mode this feature exists to remove, just triggered by someone else's gap
   * instead of this client's own). If it does hold the key, re-wraps it for the requester's
   * public key and submits via the existing, unmodified groupApi.submitGroupKey (already allows
   * any active member to submit a wrapped copy targeting any other active member) -- no new
   * backend code path for the key material itself, and the backend never sees the plaintext key
   * at any point in this flow.
   *
   * Deliberately does NOT skip when `requestingUserId === currentUserId`: ConnectX accounts share
   * ONE identity keypair across every device/browser (see keyManager/deviceSession's own docs),
   * so "the requester" and "this fulfiller" having the same userId is exactly the PRIMARY scenario
   * this feature exists for -- B's phone (B1) fulfilling for B's PC (B2), same account, different
   * session/IndexedDB. An earlier version of this guard excluded that case entirely (found via
   * live multi-device testing, Phase 7C) and silently defeated the whole feature for the actual
   * reported bug. Re-wrapping and re-submitting one's OWN already-correct key for one's OWN
   * account is harmless (submitWrappedKey's upsert), so there is nothing left to guard against
   * here.
   */
  async fulfillRewrapRequest(group: Group, currentUserId: number, requestingUserId: number): Promise<void> {
    if (!group) {
      return;
    }
    const resolved = await resolveInternal(group, currentUserId, false);
    if (!resolved) {
      // We don't hold the current key either -- can't help this requester. No error, no retry:
      // whichever member(s) actually hold it will react to the same broadcast.
      return;
    }
    const myPrivateKey = await keyManager.getPrivateKey(currentUserId);
    if (!myPrivateKey) {
      return;
    }
    const targetKeys = await deviceApi.getUserPublicKeys(requestingUserId).catch(() => null);
    if (!targetKeys || targetKeys.length === 0) {
      return;
    }
    try {
      const rawBase64 = await exportGroupKeyRaw(resolved.key);
      const wrapped = await encryptMessage(myPrivateKey, targetKeys[0].publicKey, rawBase64);
      await groupApi.submitGroupKey(group.id, requestingUserId, wrapped.ciphertext, wrapped.nonce, resolved.keyVersion);
    } catch (err) {
      console.warn(`[ConnectX Group E2EE] Failed to fulfill key rewrap request for user ${requestingUserId}:`, err);
    }
  },
};
