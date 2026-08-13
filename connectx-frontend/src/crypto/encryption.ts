import { keyManager, arrayBufferToBase64 } from './keyManager';

export interface EncryptedResult {
  ciphertext: string;
  nonce: string;
}

/**
 * Pure Client-Side E2EE Encryption using ECDH P-256 Key Agreement & AES-256-GCM.
 *
 * Sender computes Shared Key = ECDH(SenderPrivateKey, RecipientPublicKey).
 * Plaintext is encrypted with AES-256-GCM using a 12-byte random IV.
 * Only ciphertext and nonce are transmitted over WebSocket / stored in MySQL.
 */
export async function encryptMessage(
  senderPrivateKey: CryptoKey,
  recipientPublicKeyBase64: string,
  plaintext: string
): Promise<EncryptedResult> {
  const subtle = window.crypto.subtle;
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }

  const recipientPublicKey = await keyManager.importPublicKey(recipientPublicKeyBase64);
  if (!recipientPublicKey) {
    throw new Error('Failed to import recipient public key for ECDH key agreement.');
  }

  // Derive 256-Bit AES-GCM Shared Symmetric Key via ECDH
  const sharedKey = await subtle.deriveKey(
    {
      name: 'ECDH',
      public: recipientPublicKey,
    },
    senderPrivateKey,
    {
      name: 'AES-GCM',
      length: 256,
    },
    false,
    ['encrypt']
  );

  // Generate cryptographically secure 12-byte initialization vector (IV / nonce)
  const iv = window.crypto.getRandomValues(new Uint8Array(12));
  const encoder = new TextEncoder();
  const plaintextBuffer = encoder.encode(plaintext);

  // Perform AES-256-GCM encryption
  const ciphertextBuffer = await subtle.encrypt(
    {
      name: 'AES-GCM',
      iv,
    },
    sharedKey,
    plaintextBuffer
  );

  return {
    ciphertext: arrayBufferToBase64(ciphertextBuffer),
    nonce: arrayBufferToBase64(iv.buffer),
  };
}
