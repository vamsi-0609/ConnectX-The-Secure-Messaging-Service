import { config } from '../config/environment';
import { uploadRequest } from './apiClient';
import { MediaUploadResponse } from '../utils/mediaImage';

const MAX_CACHED_MEDIA_ITEMS = 60;

const mediaObjectUrlCache = new Map<number, string>();
const mediaBlobCache = new Map<number, Blob>();

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
  },
};
