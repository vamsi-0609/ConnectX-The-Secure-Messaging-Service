import { arrayBufferToBase64, base64ToArrayBuffer } from './keyManager';

/**
 * Generic binary AES-256-GCM media encryption -- the shared, key-source-agnostic foundation for
 * both GROUP media (an already-derived/unwrapped group shared key) and DIRECT media (Phase 6:
 * a fresh random per-message media key, itself later wrapped inside the existing Direct ECDH
 * message envelope -- see encryption.ts#encryptMessage). This module knows nothing about WHERE a
 * CryptoKey came from; every function here takes one as a plain parameter and never resolves,
 * caches, or fetches a key itself. Key resolution (GroupKeyManager for GROUP, the decrypted
 * message envelope for DIRECT) is the caller's responsibility, by design -- see Phase 6
 * architecture notes on mediaApi.ts's getDecryptedGroupMediaObjectUrl for the same convention on
 * the download side.
 *
 * These are the exact same algorithm, IV length, and encoding as crypto/groupCrypto.ts's byte
 * functions (encryptBytesWithGroupKey/decryptBytesWithGroupKey) -- generalized here rather than
 * duplicated; groupCrypto now delegates to this module so there is exactly one AES-GCM byte
 * implementation in the codebase.
 */
export interface MediaEncryptedResult {
  ciphertext: ArrayBuffer;
  nonce: string;
}

const getSubtleCrypto = (): SubtleCrypto | null => {
  if (typeof window !== 'undefined' && window.crypto && window.crypto.subtle) {
    return window.crypto.subtle;
  }
  return null;
};

/**
 * Generates a fresh, random 256-bit AES-GCM media key via WebCrypto. Extractable (`true`) because
 * a DIRECT media key must eventually be exported as raw bytes and embedded -- base64-encoded --
 * inside the JSON envelope that encryptMessage() then encrypts under the existing per-recipient
 * ECDH shared key (see encryption.ts). The key is never persisted anywhere by this function; it
 * belongs to exactly one media object/message and lives only as long as the caller holds the
 * returned CryptoKey (or its exported form, itself only ever handed to encryptMessage's plaintext
 * input -- never to localStorage, a URL, or a log).
 */
export async function generateMediaKey(): Promise<CryptoKey> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  return subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}

/**
 * Exports a media key's raw bytes as base64, for embedding inside a DIRECT message's ECDH-
 * encrypted envelope. Callers must encrypt this value immediately via encryptMessage() -- it must
 * never itself be transmitted, logged, or stored in plaintext.
 */
export async function exportMediaKeyRaw(key: CryptoKey): Promise<string> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const raw = await subtle.exportKey('raw', key);
  return arrayBufferToBase64(raw);
}

/** Re-imports a media key from the raw base64 recovered from a decrypted DIRECT message envelope. */
export async function importMediaKeyRaw(rawBase64: string): Promise<CryptoKey> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const raw = base64ToArrayBuffer(rawBase64);
  return subtle.importKey('raw', raw, { name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}

/**
 * Encrypts raw binary media bytes (image/document/video content) under the given AES-GCM key with
 * a fresh random 12-byte nonce -- the `mediaNonce`. This nonce protects the FILE BYTES only; it is
 * a completely separate value from the `messageNonce` that protects the ECDH-encrypted envelope
 * (caption/filename/mediaKey) riding alongside it in Message.nonce. Never reuse either nonce for
 * more than one AES-GCM operation under the same key.
 */
export async function encryptMediaBytes(key: CryptoKey, plaintext: ArrayBuffer): Promise<MediaEncryptedResult> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const iv = window.crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext);
  return {
    ciphertext,
    nonce: arrayBufferToBase64(iv.buffer),
  };
}

/** Decrypts media bytes previously produced by encryptMediaBytes, given the same key and mediaNonce. */
export async function decryptMediaBytes(key: CryptoKey, ciphertext: ArrayBuffer, mediaNonceBase64: string): Promise<ArrayBuffer> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const ivBuffer = base64ToArrayBuffer(mediaNonceBase64);
  return subtle.decrypt({ name: 'AES-GCM', iv: new Uint8Array(ivBuffer) }, key, ciphertext);
}
