import { arrayBufferToBase64, base64ToArrayBuffer } from './keyManager';

/**
 * AES-256-GCM primitives for the GROUP shared-key E2EE design (docs/CONNECTX_GROUP_ARCHITECTURE.md
 * §21). Deliberately NOT a new cryptographic scheme -- the exact same algorithm, IV length, and
 * base64 encoding as crypto/encryption.ts + crypto/decryption.ts (DIRECT), just applied directly to
 * an already-derived symmetric AES key instead of first deriving one via ECDH. A GROUP message key
 * IS the group's shared AES key, with no per-message key agreement step -- that's the entire point
 * of the shared-group-key design (one ciphertext regardless of member count).
 *
 * Wrapping a GROUP_KEY for a specific member reuses encryptMessage/decryptMessage AS-IS (see
 * groupKeyManager.ts) -- the "plaintext" is just the group key's own raw bytes, base64-encoded.
 * That is the ONLY place ECDH is involved for groups; nothing here does key agreement.
 */
export interface GroupEncryptedResult {
  ciphertext: string;
  nonce: string;
}

const getSubtleCrypto = (): SubtleCrypto | null => {
  if (typeof window !== 'undefined' && window.crypto && window.crypto.subtle) {
    return window.crypto.subtle;
  }
  return null;
};

/** Generates a fresh, random 256-bit AES-GCM group key. Extractable, so it can be exported for
 * local caching (IndexedDB) and for wrapping to other members -- it never leaves the browser
 * unencrypted over the network either way. */
export async function generateGroupKey(): Promise<CryptoKey> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  return subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}

export async function exportGroupKeyRaw(key: CryptoKey): Promise<string> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const raw = await subtle.exportKey('raw', key);
  return arrayBufferToBase64(raw);
}

export async function importGroupKeyRaw(rawBase64: string): Promise<CryptoKey> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const raw = base64ToArrayBuffer(rawBase64);
  return subtle.importKey('raw', raw, { name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}

export async function encryptWithGroupKey(groupKey: CryptoKey, plaintext: string): Promise<GroupEncryptedResult> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const iv = window.crypto.getRandomValues(new Uint8Array(12));
  const encoder = new TextEncoder();
  const ciphertextBuffer = await subtle.encrypt({ name: 'AES-GCM', iv }, groupKey, encoder.encode(plaintext));
  return {
    ciphertext: arrayBufferToBase64(ciphertextBuffer),
    nonce: arrayBufferToBase64(iv.buffer),
  };
}

export async function decryptWithGroupKey(groupKey: CryptoKey, ciphertextBase64: string, nonceBase64: string): Promise<string> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const ciphertextBuffer = base64ToArrayBuffer(ciphertextBase64);
  const ivBuffer = base64ToArrayBuffer(nonceBase64);
  const decryptedBuffer = await subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(ivBuffer) },
    groupKey,
    ciphertextBuffer
  );
  return new TextDecoder('utf-8').decode(decryptedBuffer);
}

export interface GroupEncryptedBytesResult {
  ciphertext: ArrayBuffer;
  nonce: string;
}

/**
 * GROUP media (Part 9 hardening stage): byte-identical AES-GCM parameters to
 * encryptWithGroupKey/decryptWithGroupKey above (same key type, same 12-byte random IV, no AAD) --
 * the only difference is skipping the UTF-8 text round-trip so arbitrary binary content (image/
 * video/file bytes) can be encrypted directly without first being (mis)treated as a string. The
 * ciphertext is returned as raw bytes, not base64 -- callers upload it directly as a Blob/file part
 * rather than inflating it ~33% through base64 first; the small nonce still travels as base64
 * alongside it (mirrors how MessageMedia.nonce is stored/transmitted).
 */
export async function encryptBytesWithGroupKey(groupKey: CryptoKey, plaintext: ArrayBuffer): Promise<GroupEncryptedBytesResult> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const iv = window.crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await subtle.encrypt({ name: 'AES-GCM', iv }, groupKey, plaintext);
  return {
    ciphertext,
    nonce: arrayBufferToBase64(iv.buffer),
  };
}

export async function decryptBytesWithGroupKey(groupKey: CryptoKey, ciphertext: ArrayBuffer, nonceBase64: string): Promise<ArrayBuffer> {
  const subtle = getSubtleCrypto();
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }
  const ivBuffer = base64ToArrayBuffer(nonceBase64);
  return subtle.decrypt({ name: 'AES-GCM', iv: new Uint8Array(ivBuffer) }, groupKey, ciphertext);
}
