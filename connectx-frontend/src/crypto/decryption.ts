import { keyManager, base64ToArrayBuffer } from './keyManager';

/**
 * Pure Client-Side E2EE Decryption using ECDH P-256 Key Agreement & AES-256-GCM.
 *
 * Recipient computes Shared Key = ECDH(MyPrivateKey, PeerPublicKey).
 * Ciphertext is decrypted with AES-256-GCM using the provided nonce/IV.
 * Returns the exact original plaintext message string.
 */
export async function decryptMessage(
  myPrivateKey: CryptoKey,
  peerPublicKeyBase64: string,
  ciphertextBase64: string,
  nonceBase64: string
): Promise<string> {
  const subtle = window.crypto.subtle;
  if (!subtle) {
    throw new Error('Web Crypto API (SubtleCrypto) is unavailable in this environment.');
  }

  const peerPublicKey = await keyManager.importPublicKey(peerPublicKeyBase64);
  if (!peerPublicKey) {
    throw new Error('Failed to import peer public key for ECDH decryption.');
  }

  // Derive 256-Bit AES-GCM Shared Symmetric Key via ECDH
  const sharedKey = await subtle.deriveKey(
    {
      name: 'ECDH',
      public: peerPublicKey,
    },
    myPrivateKey,
    {
      name: 'AES-GCM',
      length: 256,
    },
    false,
    ['decrypt']
  );

  const ciphertextBuffer = base64ToArrayBuffer(ciphertextBase64);
  const ivBuffer = base64ToArrayBuffer(nonceBase64);

  // Perform AES-256-GCM decryption
  const decryptedBuffer = await subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: new Uint8Array(ivBuffer),
    },
    sharedKey,
    ciphertextBuffer
  );

  const decoder = new TextDecoder('utf-8');
  return decoder.decode(decryptedBuffer);
}
