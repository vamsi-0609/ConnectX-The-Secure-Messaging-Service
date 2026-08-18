import { config } from '../config/environment';

// The profile-image endpoint now enforces visibility server-side (blocking/connection-based),
// which requires the request to be authenticated. A plain <img src> can't send an Authorization
// header, so the token rides along as a query param instead -- JwtAuthenticationFilter already
// accepts one (originally added for the WS handshake), we're just reusing it here.
function withAuthToken(url: string): string {
  const token = localStorage.getItem('connectx_token');
  if (!token) {
    return url;
  }
  const separator = url.includes('?') ? '&' : '?';
  return `${url}${separator}token=${encodeURIComponent(token)}`;
}

export function resolveProfileImageUrl(profileImageUrl?: string | null): string | undefined {
  if (!profileImageUrl) {
    return undefined;
  }

  // Local previews from file picker / camera -- never our API, no token needed
  if (profileImageUrl.startsWith('blob:') || profileImageUrl.startsWith('data:')) {
    return profileImageUrl;
  }
  if (profileImageUrl.startsWith('http://') || profileImageUrl.startsWith('https://')) {
    return withAuthToken(profileImageUrl);
  }

  // Server-relative API path
  if (profileImageUrl.startsWith('/api/v1/')) {
    const apiBase = config.apiBaseUrl.replace(/\/$/, '');
    if (apiBase.startsWith('http://') || apiBase.startsWith('https://')) {
      const pathSuffix = profileImageUrl.replace(/^\/api\/v1/, '');
      return withAuthToken(`${apiBase}${pathSuffix}`);
    }
    return withAuthToken(profileImageUrl);
  }

  return withAuthToken(`${config.apiBaseUrl}${profileImageUrl.startsWith('/') ? '' : '/'}${profileImageUrl}`);
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
