import { cryptoStorage, LocalDeviceMetadata } from './storage';

export interface GeneratedKeyPair {
  publicKeyBase64: string;
  privateKey: CryptoKey | null;
  publicKey: CryptoKey | null;
}

// Convert ArrayBuffer to Base64 String
export function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary);
}

// Convert Base64 String to ArrayBuffer
export function base64ToArrayBuffer(base64: string): ArrayBuffer {
  const binaryString = window.atob(base64);
  const len = binaryString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes.buffer;
}

const getSubtleCrypto = (): SubtleCrypto | null => {
  if (typeof window !== 'undefined' && window.crypto && window.crypto.subtle) {
    return window.crypto.subtle;
  }
  return null;
};

export const keyManager = {
  /**
   * Generate an ECDH P-256 keypair for E2EE key agreement.
   * Handles secure and non-secure context fallbacks gracefully.
   */
  async generateKeyPair(userId: number): Promise<GeneratedKeyPair> {
    const subtle = getSubtleCrypto();

    if (!subtle) {
      console.warn('[ConnectX Crypto] SubtleCrypto unavailable in non-secure HTTP context. Generating fallback key placeholder.');
      // Create a deterministic fallback base64 string for non-HTTPS IP access
      const fallbackKeyBase64 = window.btoa(`FALLBACK_PUBLIC_KEY_USER_${userId}_${Date.now()}`);
      return {
        publicKeyBase64: fallbackKeyBase64,
        privateKey: null,
        publicKey: null,
      };
    }

    try {
      const keyPair = await subtle.generateKey(
        {
          name: 'ECDH',
          namedCurve: 'P-256',
        },
        true, // extractable public key
        ['deriveKey', 'deriveBits']
      );

      // Export public key as SPKI Base64 string
      const exportedPublicKey = await subtle.exportKey('spki', keyPair.publicKey);
      const publicKeyBase64 = arrayBufferToBase64(exportedPublicKey);

      // Save private key & public key base64 securely in IndexedDB
      await cryptoStorage.savePrivateKey(userId, keyPair.privateKey, publicKeyBase64);

      return {
        publicKeyBase64,
        privateKey: keyPair.privateKey,
        publicKey: keyPair.publicKey,
      };
    } catch (err) {
      console.error('[ConnectX Crypto] Key generation error:', err);
      const fallbackKeyBase64 = window.btoa(`KEY_USER_${userId}`);
      return {
        publicKeyBase64: fallbackKeyBase64,
        privateKey: null,
        publicKey: null,
      };
    }
  },

  /**
   * Import recipient's Base64 SPKI public key into a CryptoKey object.
   */
  async importPublicKey(spkiBase64: string): Promise<CryptoKey | null> {
    const subtle = getSubtleCrypto();
    if (!subtle) return null;

    try {
      const spkiBuffer = base64ToArrayBuffer(spkiBase64);
      return await subtle.importKey(
        'spki',
        spkiBuffer,
        {
          name: 'ECDH',
          namedCurve: 'P-256',
        },
        true,
        []
      );
    } catch (err) {
      console.warn('Failed to import public key:', err);
      return null;
    }
  },

  /**
   * Export private CryptoKey as PKCS#8 Base64 string for multi-device key sync.
   */
  async exportPrivateKey(privateKey: CryptoKey): Promise<string | null> {
    const subtle = getSubtleCrypto();
    if (!subtle) return null;
    try {
      const pkcs8Buffer = await subtle.exportKey('pkcs8', privateKey);
      return arrayBufferToBase64(pkcs8Buffer);
    } catch (err) {
      console.error('Failed to export private key to PKCS8:', err);
      return null;
    }
  },

  /**
   * Import PKCS#8 Base64 string into a private CryptoKey object.
   */
  async importPrivateKeyFromPKCS8(pkcs8Base64: string): Promise<CryptoKey | null> {
    const subtle = getSubtleCrypto();
    if (!subtle) return null;
    try {
      const pkcs8Buffer = base64ToArrayBuffer(pkcs8Base64);
      return await subtle.importKey(
        'pkcs8',
        pkcs8Buffer,
        {
          name: 'ECDH',
          namedCurve: 'P-256',
        },
        true,
        ['deriveKey', 'deriveBits']
      );
    } catch (err) {
      console.error('Failed to import private key from PKCS8:', err);
      return null;
    }
  },

  async saveKeyVault(userId: number, privateKey: CryptoKey, publicKeyBase64: string): Promise<void> {
    return cryptoStorage.savePrivateKey(userId, privateKey, publicKeyBase64);
  },

  /**
   * Retrieve existing key vault ({ privateKey, publicKeyBase64 }) from IndexedDB.
   */
  async getKeyVault(userId: number) {
    const subtle = getSubtleCrypto();
    if (!subtle) return null;
    return cryptoStorage.getPrivateKey(userId);
  },

  /**
   * Retrieve existing private key from IndexedDB.
   */
  async getPrivateKey(userId: number): Promise<CryptoKey | null> {
    const vault = await this.getKeyVault(userId);
    return vault ? vault.privateKey : null;
  },

  async saveLocalDevice(userId: number, metadata: LocalDeviceMetadata): Promise<void> {
    return cryptoStorage.saveLocalDevice(userId, metadata);
  },

  async getLocalDevice(userId: number): Promise<LocalDeviceMetadata | null> {
    return cryptoStorage.getLocalDevice(userId);
  },

  async saveDecryptedMessage(messageId: number, decryptedContent: string): Promise<void> {
    return cryptoStorage.saveDecryptedMessage(messageId, decryptedContent);
  },

  async getDecryptedMessage(messageId: number): Promise<string | null> {
    return cryptoStorage.getDecryptedMessage(messageId);
  },

  async clearAllDecryptedMessages(): Promise<void> {
    return cryptoStorage.clearAllDecryptedMessages();
  },

  async clearKeys(): Promise<void> {
    return cryptoStorage.clearKeys();
  },
};

