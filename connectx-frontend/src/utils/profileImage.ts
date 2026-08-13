import { config } from '../config/environment';

export function resolveProfileImageUrl(profileImageUrl?: string | null): string | undefined {
  if (!profileImageUrl) {
    return undefined;
  }

  // Local previews from file picker / camera
  if (
    profileImageUrl.startsWith('blob:') ||
    profileImageUrl.startsWith('data:') ||
    profileImageUrl.startsWith('http://') ||
    profileImageUrl.startsWith('https://')
  ) {
    return profileImageUrl;
  }

  // Server-relative API path
  if (profileImageUrl.startsWith('/api/v1/')) {
    const apiBase = config.apiBaseUrl.replace(/\/$/, '');
    if (apiBase.startsWith('http://') || apiBase.startsWith('https://')) {
      const pathSuffix = profileImageUrl.replace(/^\/api\/v1/, '');
      return `${apiBase}${pathSuffix}`;
    }
    return profileImageUrl;
  }

  return `${config.apiBaseUrl}${profileImageUrl.startsWith('/') ? '' : '/'}${profileImageUrl}`;
}

export const PROFILE_PHOTO_MAX_BYTES = 15 * 1024 * 1024;
export const PROFILE_PHOTO_ACCEPT = 'image/jpeg,image/png,image/webp';

export function validateProfilePhotoFile(file: File): string | null {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];
  if (!allowedTypes.includes(file.type)) {
    return 'Only JPG, PNG, and WEBP images are allowed.';
  }
  if (file.size > PROFILE_PHOTO_MAX_BYTES) {
    return 'Profile photo must be 15 MB or smaller.';
  }
  return null;
}
