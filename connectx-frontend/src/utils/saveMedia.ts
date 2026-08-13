import { mediaApi } from '../api/mediaApi';

function extensionForMimeType(mimeType?: string): string {
  if (mimeType?.includes('png')) return 'png';
  if (mimeType?.includes('webp')) return 'webp';
  return 'jpg';
}

async function blobFromSource(mediaId?: number, localMediaUrl?: string): Promise<Blob> {
  if (localMediaUrl) {
    const response = await fetch(localMediaUrl);
    return response.blob();
  }

  if (mediaId != null) {
    return mediaApi.getMediaBlob(mediaId);
  }

  throw new Error('No image available to save.');
}

export async function saveImageToGallery(options: {
  mediaId?: number;
  localMediaUrl?: string;
  mimeType?: string;
  filenamePrefix?: string;
}): Promise<void> {
  const blob = await blobFromSource(options.mediaId, options.localMediaUrl);
  const mimeType = options.mimeType || blob.type || 'image/jpeg';
  const extension = extensionForMimeType(mimeType);
  const prefix = options.filenamePrefix || 'connectx';
  const filename = `${prefix}-${Date.now()}.${extension}`;

  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();

  window.setTimeout(() => {
    URL.revokeObjectURL(objectUrl);
  }, 1000);
}

export async function saveImageUrlToGallery(imageUrl: string, filenamePrefix = 'connectx-profile'): Promise<void> {
  const response = await fetch(imageUrl);
  if (!response.ok) {
    throw new Error('Failed to download image.');
  }

  const blob = await response.blob();
  const mimeType = blob.type || 'image/jpeg';
  const extension = extensionForMimeType(mimeType);
  const filename = `${filenamePrefix}-${Date.now()}.${extension}`;

  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();

  window.setTimeout(() => {
    URL.revokeObjectURL(objectUrl);
  }, 1000);
}
