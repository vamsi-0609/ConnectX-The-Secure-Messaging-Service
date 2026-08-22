/**
 * IndexedDB storage for non-extractable client private keys.
 * Ensures user private keys never leave the browser and persist safely across reloads.
 */

/**
 * IndexedDB storage for non-extractable client private keys and decrypted message vault.
 * Ensures user private keys never leave the browser and persist safely across reloads.
 */

const DB_NAME = 'ConnectX_Crypto_Vault';
// Phase 7D-2: bumped 6 -> 7. A browser that reached version 6 BEFORE commit 33e007a added
// GROUP_KEY_STORE_NAME's creation to onupgradeneeded (same commit that bumped 5 -> 6, but some
// browsers' on-disk DB was already sitting at 6 from an earlier, unrelated version-6 open before
// that code shipped -- confirmed live on one such browser: version 6, group_keys absent) will
// never re-run onupgradeneeded at version 6, since IndexedDB only fires it when the requested
// version is HIGHER than the existing one. Every store-creation check below is already
// idempotent (`if (!contains(...))`), so this bump is the only change needed: it forces
// onupgradeneeded to run once more for any browser below 7, safely no-ops for the three stores
// that already exist, and creates only the missing group_keys store. A browser already correctly
// at 6 with all four stores is unaffected until it independently reaches 7 (also a no-op then).
// Phase 7G-2A: bumped 7 -> 8. Same rationale as the 6 -> 7 bump above -- forces onupgradeneeded
// to run once more so every browser (regardless of which version it's currently sitting at)
// creates the new ACCOUNTS_STORE_NAME below. No existing store is touched by this bump; the
// account-scoped lifecycle logic that will eventually read/write ACCOUNTS_STORE_NAME is a later,
// separate phase -- this change only makes the store exist.
const DB_VERSION = 8;
const STORE_NAME = 'private_keys';
const DEVICE_STORE_NAME = 'device_metadata';
const DECRYPTED_MSG_STORE_NAME = 'decrypted_messages';
// GROUP shared-key cache -- one raw AES-256 key per (groupId, keyVersion), keyed
// `group_${groupId}_v${keyVersion}`. Never a DIRECT/identity private key -- a completely separate
// store from STORE_NAME, matching Part 21/28's "group crypto isolated from DIRECT crypto"
// requirement at the storage layer too.
const GROUP_KEY_STORE_NAME = 'group_keys';
// Phase 7G-2A: local account-lifecycle registry (schema only -- nothing reads/writes this store
// yet). Exists to eventually let logout/account-removal scope crypto-vault cleanup to ONE account
// instead of the whole vault, without ever touching GROUP_KEY_STORE_NAME's shared, non-account-
// scoped key material.
const ACCOUNTS_STORE_NAME = 'accounts';

export interface LocalDeviceMetadata {
  deviceId: number;
  publicKey: string;
  keyAlgorithm: string;
}

export interface StoredKeyVault {
  privateKey: CryptoKey;
  publicKeyBase64: string;
}

// A fresh connection used to be opened (and never closed) on every single storage call.
// IndexedDB connections are safe to hold open across many transactions, so it's memoized
// here instead. onversionchange closes it and clears the memo so a future schema bump
// (this tab reloaded on a new deploy, or another tab) doesn't leave a stale connection
// blocking the upgrade; onerror clears it too so a failed open can be retried.
let dbConnectionPromise: Promise<IDBDatabase> | null = null;

function openDB(): Promise<IDBDatabase> {
  if (dbConnectionPromise) {
    return dbConnectionPromise;
  }

  dbConnectionPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
      if (!db.objectStoreNames.contains(DEVICE_STORE_NAME)) {
        db.createObjectStore(DEVICE_STORE_NAME);
      }
      if (!db.objectStoreNames.contains(DECRYPTED_MSG_STORE_NAME)) {
        db.createObjectStore(DECRYPTED_MSG_STORE_NAME);
      }
      if (!db.objectStoreNames.contains(GROUP_KEY_STORE_NAME)) {
        db.createObjectStore(GROUP_KEY_STORE_NAME);
      }
      if (!db.objectStoreNames.contains(ACCOUNTS_STORE_NAME)) {
        db.createObjectStore(ACCOUNTS_STORE_NAME);
      }
    };

    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => {
        db.close();
        dbConnectionPromise = null;
      };
      resolve(db);
    };
    request.onerror = () => {
      dbConnectionPromise = null;
      reject(request.error);
    };
  });

  return dbConnectionPromise;
}

export const cryptoStorage = {
  async savePrivateKey(userId: number, privateKey: CryptoKey, publicKeyBase64: string): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const data: StoredKeyVault = { privateKey, publicKeyBase64 };
      const request = store.put(data, `key_user_${userId}`);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  },

  async getPrivateKey(userId: number): Promise<StoredKeyVault | null> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.get(`key_user_${userId}`);

      request.onsuccess = () => {
        const result = request.result;
        if (!result) return resolve(null);
        // Handle legacy storage format (where result was pure CryptoKey)
        if (result instanceof CryptoKey) {
          resolve({ privateKey: result, publicKeyBase64: '' });
        } else {
          resolve(result as StoredKeyVault);
        }
      };
      request.onerror = () => reject(request.error);
    });
  },

  async saveLocalDevice(userId: number, metadata: LocalDeviceMetadata): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(DEVICE_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(DEVICE_STORE_NAME);
      const request = store.put(metadata, `device_user_${userId}`);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  },

  async getLocalDevice(userId: number): Promise<LocalDeviceMetadata | null> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(DEVICE_STORE_NAME, 'readonly');
      const store = transaction.objectStore(DEVICE_STORE_NAME);
      const request = store.get(`device_user_${userId}`);

      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error);
    });
  },

  async saveDecryptedMessage(messageId: number, decryptedContent: string): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(DECRYPTED_MSG_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(DECRYPTED_MSG_STORE_NAME);
      const request = store.put(decryptedContent, `msg_${messageId}`);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  },

  async getDecryptedMessage(messageId: number): Promise<string | null> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(DECRYPTED_MSG_STORE_NAME, 'readonly');
      const store = transaction.objectStore(DECRYPTED_MSG_STORE_NAME);
      const request = store.get(`msg_${messageId}`);

      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error);
    });
  },

  async clearAllDecryptedMessages(): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(DECRYPTED_MSG_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(DECRYPTED_MSG_STORE_NAME);
      const request = store.clear();

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  },

  async deleteDecryptedMessage(messageId: number): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(DECRYPTED_MSG_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(DECRYPTED_MSG_STORE_NAME);
      const request = store.delete(`msg_${messageId}`);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  },

  async clearKeys(): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(
        [STORE_NAME, DEVICE_STORE_NAME, DECRYPTED_MSG_STORE_NAME, GROUP_KEY_STORE_NAME],
        'readwrite'
      );
      transaction.objectStore(STORE_NAME).clear();
      transaction.objectStore(DEVICE_STORE_NAME).clear();
      transaction.objectStore(DECRYPTED_MSG_STORE_NAME).clear();
      transaction.objectStore(GROUP_KEY_STORE_NAME).clear();

      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
  },

  // GROUP shared-key cache. Stores the raw AES key as base64 (not a CryptoKey object -- unlike
  // STORE_NAME's non-extractable DIRECT identity keys, a GROUP key is deliberately extractable so
  // it can be wrapped for other members; base64 is simplest to serialize into IndexedDB).
  async saveGroupKeyRaw(groupId: number, keyVersion: number, rawBase64: string): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(GROUP_KEY_STORE_NAME, 'readwrite');
      const request = transaction.objectStore(GROUP_KEY_STORE_NAME).put(rawBase64, `group_${groupId}_v${keyVersion}`);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  },

  async getGroupKeyRaw(groupId: number, keyVersion: number): Promise<string | null> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(GROUP_KEY_STORE_NAME, 'readonly');
      const request = transaction.objectStore(GROUP_KEY_STORE_NAME).get(`group_${groupId}_v${keyVersion}`);
      request.onsuccess = () => resolve((request.result as string | undefined) ?? null);
      request.onerror = () => reject(request.error);
    });
  },

  // Removes every cached key version for one group -- used when the group is deleted or the
  // current user leaves/is removed, so no stale group key material lingers in this browser for a
  // group the user no longer has any relationship to.
  async clearGroupKeys(groupId: number): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(GROUP_KEY_STORE_NAME, 'readwrite');
      const store = transaction.objectStore(GROUP_KEY_STORE_NAME);
      const prefix = `group_${groupId}_v`;
      const request = store.openCursor();
      request.onsuccess = () => {
        const cursor = request.result;
        if (cursor) {
          if (typeof cursor.key === 'string' && cursor.key.startsWith(prefix)) {
            cursor.delete();
          }
          cursor.continue();
        }
      };
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
  },
};

