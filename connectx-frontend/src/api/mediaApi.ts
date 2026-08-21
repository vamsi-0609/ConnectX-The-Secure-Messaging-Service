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

  // Shared encrypted-upload plumbing for both GROUP (Part 9) and DIRECT (Phase 6) media.
  // `encryptedBytes` is already-encrypted ciphertext (see crypto/mediaCrypto.ts#encryptMediaBytes /
  // crypto/groupCrypto.ts#encryptBytesWithGroupKey) -- this function never sees, produces, or could
  // produce plaintext; it only carries the caller's bytes plus the metadata
  // MediaService#uploadConversationMedia needs to validate the encryption contract server-side.
  // `groupKeyVersion` is GROUP-only -- omit it for a DIRECT upload, exactly as the backend expects
  // (see MediaService: DIRECT never persists a groupKeyVersion). The media key itself is NEVER
  // part of this call -- only the caller-supplied ciphertext and mediaNonce ever leave the browser
  // here; the key stays local (GROUP) or travels separately, wrapped inside the DIRECT message's
  // own ECDH envelope (Phase 6C).
  uploadEncryptedMedia: (
    conversationId: number,
    encryptedBytes: ArrayBuffer,
    mediaNonce: string,
    mimeType: string,
    options?: { groupKeyVersion?: number; originalFilename?: string }
  ) => {
    const formData = new FormData();
    formData.append(
      'file',
      new Blob([encryptedBytes], { type: 'application/octet-stream' }),
      options?.originalFilename || 'encrypted.bin'
    );
    formData.append('nonce', mediaNonce);
    formData.append('mimeType', mimeType);
    if (options?.groupKeyVersion !== undefined) {
      formData.append('groupKeyVersion', String(options.groupKeyVersion));
    }
    return uploadRequest<MediaUploadResponse>(`/conversations/${conversationId}/media`, formData);
  },

  // GROUP E2EE media only -- unchanged call signature, now delegating to the shared
  // uploadEncryptedMedia above instead of duplicating the FormData construction.
  uploadEncryptedGroupMedia: (
    conversationId: number,
    encryptedBytes: ArrayBuffer,
    nonce: string,
    groupKeyVersion: number,
    mimeType: string,
    originalFilename?: string
  ) => {
    return mediaApi.uploadEncryptedMedia(conversationId, encryptedBytes, nonce, mimeType, {
      groupKeyVersion,
      originalFilename,
    });
  },

  // DIRECT encrypted media (Phase 6 foundation). No groupKeyVersion -- DIRECT has no shared/
  // versioned key; the media key that decrypts `encryptedBytes` travels wrapped inside the
  // DIRECT message's own ECDH-encrypted envelope (Message.ciphertext/nonce), never here.
  uploadEncryptedDirectMedia: (
    conversationId: number,
    encryptedBytes: ArrayBuffer,
    mediaNonce: string,
    mimeType: string,
    originalFilename?: string
  ) => {
    return mediaApi.uploadEncryptedMedia(conversationId, encryptedBytes, mediaNonce, mimeType, {
      originalFilename,
    });
  },

  // E2EE media download foundation, shared by GROUP (Part 9) and DIRECT (Phase 6). `decrypt` is
  // supplied by the caller (never imported into this module directly) so this stays entirely
  // key-source-agnostic -- it just fetches the ciphertext (reusing getMediaBlob's own cache) and
  // hands it to the caller's decrypt function, then caches the DECRYPTED result separately, keyed
  // by mediaId, so repeated renders of the same already-decrypted message never re-fetch or
  // re-decrypt. Key resolution is deliberately NOT this module's job: GROUP callers resolve a
  // group key via GroupKeyManager (crypto/groupMediaKey.ts), DIRECT callers will extract a
  // mediaKey from the DIRECT message's decrypted ECDH envelope (Phase 6C) -- either way, by the
  // time `decrypt` runs here it already has everything it needs.
  getDecryptedMediaObjectUrl: async (
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

  // GROUP E2EE media only -- unchanged name/signature (ImageMessageContent/DocumentMessageContent
  // call this directly), now delegating to the generic getDecryptedMediaObjectUrl above so GROUP
  // and the eventual DIRECT caller (Phase 6D) share one implementation and one decrypted-object-URL
  // cache.
  getDecryptedGroupMediaObjectUrl: async (
    mediaId: number,
    mimeType: string,
    decrypt: (ciphertext: ArrayBuffer) => Promise<ArrayBuffer>
  ): Promise<string> => {
    return mediaApi.getDecryptedMediaObjectUrl(mediaId, mimeType, decrypt);
  },
};
