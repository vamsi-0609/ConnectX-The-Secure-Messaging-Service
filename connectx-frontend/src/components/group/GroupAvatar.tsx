import React, { useEffect, useState } from 'react';
import { Users } from 'lucide-react';
import { resolveProfileImageUrl } from '../../utils/profileImage';

type AvatarSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

interface GroupAvatarProps {
  name: string;
  avatarUrl?: string | null;
  size?: AvatarSize;
  className?: string;
}

// Mirrors UserAvatar's size scale exactly so a group row/header sits flush next to 1:1 rows using
// the same sizes. No tap-to-zoom (groups have no photo upload yet -- see CreateGroupModal), no
// "passive" concept (a group avatar is always decorative).
const SIZE_CLASSES: Record<AvatarSize, string> = {
  xs: 'w-8 h-8 text-xs',
  sm: 'w-9 h-9 text-xs',
  md: 'w-11 h-11 md:w-12 md:h-12 text-sm md:text-base',
  lg: 'w-16 h-16 text-xl',
  xl: 'w-24 h-24 text-3xl',
};

const ICON_SIZE: Record<AvatarSize, string> = {
  xs: 'w-3.5 h-3.5',
  sm: 'w-4 h-4',
  md: 'w-5 h-5',
  lg: 'w-7 h-7',
  xl: 'w-10 h-10',
};

export const GroupAvatar: React.FC<GroupAvatarProps> = ({ name, avatarUrl, size = 'md', className = '' }) => {
  const imageUrl = resolveProfileImageUrl(avatarUrl ?? undefined);
  // Mirrors UserAvatar's identical onError fallback -- without it, a stale/removed/still-
  // propagating avatar URL (e.g. right after a key/photo change, or a deleted file) rendered a
  // permanently broken image icon instead of falling back to the decorative Users icon.
  const [imageError, setImageError] = useState(false);

  useEffect(() => {
    setImageError(false);
  }, [avatarUrl]);

  return (
    <div
      className={`rounded-full overflow-hidden bg-gradient-to-tr from-emerald-500 to-teal-500 flex items-center justify-center font-semibold text-white flex-shrink-0 select-none ${SIZE_CLASSES[size]} ${className}`}
      aria-hidden
    >
      {imageUrl && !imageError ? (
        <img
          src={imageUrl}
          alt={name}
          className="w-full h-full object-cover pointer-events-none"
          onError={() => setImageError(true)}
        />
      ) : (
        <Users className={ICON_SIZE[size]} />
      )}
    </div>
  );
};
