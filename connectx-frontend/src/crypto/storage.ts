/**
 * IndexedDB storage for non-extractable client private keys.
 * Ensures user private keys never leave the browser and persist safely across reloads.
 */

/**
 * IndexedDB storage for non-extractable client private keys and decrypted message vault.
 * Ensures user private keys never leave the browser and persist safely across reloads.
 */

const DB_NAME = 'ConnectX_Crypto_Vault';
const DB_VERSION = 5;
const STORE_NAME = 'private_keys';
const DEVICE_STORE_NAME = 'device_metadata';
const DECRYPTED_MSG_STORE_NAME = 'decrypted_messages';

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

  async clearKeys(): Promise<void> {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(
        [STORE_NAME, DEVICE_STORE_NAME, DECRYPTED_MSG_STORE_NAME],
        'readwrite'
      );
      transaction.objectStore(STORE_NAME).clear();
      transaction.objectStore(DEVICE_STORE_NAME).clear();
      transaction.objectStore(DECRYPTED_MSG_STORE_NAME).clear();

      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
  },
};

