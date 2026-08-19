// Client-side validation for group avatars. Same constraints as profile photos (JPG/PNG/WEBP,
// 15MB -- see utils/profileImage.ts and LocalGroupImageStorage's server-side mirror of the same
// limits), kept as a separate module with group-appropriate copy so a group photo error never
// reads as if it were about the user's own profile photo.
export const GROUP_PHOTO_MAX_BYTES = 15 * 1024 * 1024;
export const GROUP_PHOTO_ACCEPT = 'image/jpeg,image/png,image/webp';

export function validateGroupPhotoFile(file: File): string | null {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];
  if (!allowedTypes.includes(file.type)) {
    return 'Only JPG, PNG, and WEBP images are allowed.';
  }
  if (file.size > GROUP_PHOTO_MAX_BYTES) {
    return 'Group photo must be 15 MB or smaller.';
  }
  return null;
}
