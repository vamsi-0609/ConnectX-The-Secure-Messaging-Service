export const MEDIA_IMAGE_MAX_BYTES = 10 * 1024 * 1024;
export const MEDIA_IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp';

export function validateMediaImageFile(file: File): string | null {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];
  if (!allowedTypes.includes(file.type)) {
    return 'Only JPG, PNG, and WEBP images are allowed.';
  }
  if (file.size > MEDIA_IMAGE_MAX_BYTES) {
    return 'Image must be 10 MB or smaller.';
  }
  return null;
}

export interface MediaUploadResponse {
  mediaId: number;
  conversationId: number;
  mimeType: string;
  fileSizeBytes: number;
  // GROUP E2EE media only -- present iff this upload was encrypted (see MediaService#uploadConversationMedia).
  nonce?: string;
  groupKeyVersion?: number;
}
