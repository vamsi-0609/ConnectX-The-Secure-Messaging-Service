import { config } from '../config/environment';

// The API origin profile-image URLs are allowed to carry a token to -- resolved once, not
// per-call, and computed against window.location.origin so it's correct whether config.apiBaseUrl
// is absolute (https://api.example.com) or relative (/api, dev proxy setups).
function getApiOrigin(): string | null {
  try {
    return new URL(config.apiBaseUrl, window.location.origin).origin;
  } catch {
    return null;
  }
}

// The profile-image endpoint enforces visibility server-side (blocking/connection-based), which
// requires the request to be authenticated. A plain <img src> can't send an Authorization header,
// so the token rides along as a query param instead -- JwtAuthenticationFilter already accepts one
// (originally added for the WS handshake), we're just reusing it here.
//
// SECURITY: only ever attach the token when the URL's origin is genuinely our own API. The backend
// no longer accepts arbitrary external URLs for profileImageUrl (PATCH /users/me dropped that
// field entirely), but this check stays regardless -- it's the actual boundary that prevents the
// live JWT from ever being sent to a third-party host, and must not depend solely on the backend
// having validated its input correctly.
function withAuthToken(url: string): string {
  const token = localStorage.getItem('connectx_token');
  if (!token) {
    return url;
  }
  const apiOrigin = getApiOrigin();
  let targetOrigin: string;
  try {
    targetOrigin = new URL(url, window.location.origin).origin;
  } catch {
    return url;
  }
  if (!apiOrigin || targetOrigin !== apiOrigin) {
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
