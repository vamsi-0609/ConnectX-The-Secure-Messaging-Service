import { config } from '../config/environment';
import { uploadRequest } from './apiClient';
import { MediaUploadResponse } from '../utils/mediaImage';

const MAX_CACHED_MEDIA_ITEMS = 60;

const mediaObjectUrlCache = new Map<number, string>();
const mediaBlobCache = new Map<number, Blob>();
// GROUP E2EE media only -- the DECRYPTED result, kept in a separate cache/namespace from the
// (ciphertext) caches above so a decrypted object URL is never confused with the raw encrypted
// bytes `getMediaBlob` fetches. Deliberately not decrypted-blob-cached (only the object URL) --
// decrypting is cheap relative to the network fetch it follows, and this keeps memory bounded to
// one extra Map instead of duplicating every cached item.
const decryptedMediaObjectUrlCache = new Map<number, string>();

// Bounds the caches below so long sessions / media-heavy scrolling don't pin an
// ever-growing set of blobs and their object URLs in memory for the rest of the tab's
// life. FIFO eviction (oldest-inserted first), mirroring conversationCache's pattern.
function evictOldestMediaIfAtCapacity(): void {
  if (mediaBlobCache.size < MAX_CACHED_MEDIA_ITEMS) return;
  const oldestId = mediaBlobCache.keys().next().value;
  if (oldestId !== undefined) {
    mediaApi.revokeMediaObjectUrl(oldestId);
  }
}

export const mediaApi = {
  uploadImage: (conversationId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return uploadRequest<MediaUploadResponse>(`/conversations/${conversationId}/media`, formData);
  },

  uploadMediaFile: (conversationId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return uploadRequest<MediaUploadResponse>(`/conversations/${conversationId}/media`, formData);
  },

  getMediaBlob: async (mediaId: number): Promise<Blob> => {
    const cachedBlob = mediaBlobCache.get(mediaId);
    if (cachedBlob) {
      return cachedBlob;
    }

    const token = localStorage.getItem('connectx_token');
    const response = await fetch(`${config.apiBaseUrl}/media/${mediaId}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });

    if (!response.ok) {
      throw new Error('Failed to load media');
    }

    const blob = await response.blob();
    evictOldestMediaIfAtCapacity();
    mediaBlobCache.set(mediaId, blob);
    return blob;
  },

  getMediaObjectUrl: async (mediaId: number): Promise<string> => {
    const cached = mediaObjectUrlCache.get(mediaId);
    if (cached) {
      return cached;
    }

    const blob = await mediaApi.getMediaBlob(mediaId);
    const objectUrl = URL.createObjectURL(blob);
    mediaObjectUrlCache.set(mediaId, objectUrl);
    return objectUrl;
  },

  revokeMediaObjectUrl: (mediaId: number) => {
    const cached = mediaObjectUrlCache.get(mediaId);
    if (cached) {
      URL.revokeObjectURL(cached);
      mediaObjectUrlCache.delete(mediaId);
    }
    mediaBlobCache.delete(mediaId);
    const decryptedCached = decryptedMediaObjectUrlCache.get(mediaId);
    if (decryptedCached) {
      URL.revokeObjectURL(decryptedCached);
      decryptedMediaObjectUrlCache.delete(mediaId);
    }
  },

  // GROUP E2EE media only. `encryptedBytes` is already-encrypted ciphertext (see
  // crypto/groupCrypto.ts#encryptBytesWithGroupKey) -- this function never sees or could produce
  // plaintext; it only carries the caller's bytes to the upload endpoint alongside the metadata
  // MediaService#uploadConversationMedia needs to validate the encryption itself.
  uploadEncryptedGroupMedia: (
    conversationId: number,
    encryptedBytes: ArrayBuffer,
    nonce: string,
    groupKeyVersion: number,
    mimeType: string,
    originalFilename?: string
  ) => {
    const formData = new FormData();
    formData.append('file', new Blob([encryptedBytes], { type: 'application/octet-stream' }), originalFilename || 'encrypted.bin');
    formData.append('nonce', nonce);
    formData.append('groupKeyVersion', String(groupKeyVersion));
    formData.append('mimeType', mimeType);
    return uploadRequest<MediaUploadResponse>(`/conversations/${conversationId}/media`, formData);
  },

  // GROUP E2EE media only. `decrypt` is supplied by the caller (never imported into this module
  // directly) so this stays crypto-agnostic like every other function here -- it just fetches the
  // ciphertext (reusing getMediaBlob's own cache) and hands it to the caller's decrypt function,
  // then caches the DECRYPTED result separately, keyed by mediaId, so repeated renders of the same
  // already-decrypted message never re-fetch or re-decrypt.
  getDecryptedGroupMediaObjectUrl: async (
    mediaId: number,
    mimeType: string,
    decrypt: (ciphertext: ArrayBuffer) => Promise<ArrayBuffer>
  ): Promise<string> => {
    const cached = decryptedMediaObjectUrlCache.get(mediaId);
    if (cached) {
      return cached;
    }

    const rawBlob = await mediaApi.getMediaBlob(mediaId);
    const ciphertext = await rawBlob.arrayBuffer();
    const decryptedBytes = await decrypt(ciphertext);
    const decryptedBlob = new Blob([decryptedBytes], { type: mimeType });
    const objectUrl = URL.createObjectURL(decryptedBlob);
    decryptedMediaObjectUrlCache.set(mediaId, objectUrl);
    return objectUrl;
  },
};
