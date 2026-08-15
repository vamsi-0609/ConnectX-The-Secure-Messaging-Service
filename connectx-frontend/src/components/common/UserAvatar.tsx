import React, { useEffect, useState } from 'react';
import { User } from '../../types';
import { resolveProfileImageUrl } from '../../utils/profileImage';
import { ImageViewerModal } from './ImageViewerModal';

type AvatarSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

interface UserAvatarProps {
  user: Pick<User, 'displayName' | 'username' | 'profileImageUrl'>;
  size?: AvatarSize;
  className?: string;
  viewable?: boolean;
  /** When true, avatar is decorative only (e.g. inside another button). */
  passive?: boolean;
}

const SIZE_CLASSES: Record<AvatarSize, string> = {
  xs: 'w-8 h-8 text-xs',
  sm: 'w-9 h-9 text-xs',
  md: 'w-11 h-11 md:w-12 md:h-12 text-sm md:text-base',
  lg: 'w-16 h-16 text-xl',
  xl: 'w-24 h-24 text-3xl',
};

export const UserAvatar: React.FC<UserAvatarProps> = ({
  user,
  size = 'md',
  className = '',
  viewable = true,
  passive = false,
}) => {
  const [viewerOpen, setViewerOpen] = useState(false);
  const [imageError, setImageError] = useState(false);

  const imageUrl = resolveProfileImageUrl(user.profileImageUrl);
  const initial = (user.displayName || user.username || '?').charAt(0).toUpperCase();
  const displayName = user.displayName || user.username;
  const canView = !passive && viewable && !!imageUrl && !imageError;

  useEffect(() => {
    setImageError(false);
  }, [user.profileImageUrl]);

  const handleOpenViewer = (event?: React.MouseEvent | React.KeyboardEvent) => {
    event?.stopPropagation();
    event?.preventDefault();
    if (canView) {
      setViewerOpen(true);
    }
  };

  return (
    <>
      <div
        className={`rounded-full overflow-hidden bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center font-semibold text-white flex-shrink-0 select-none ${SIZE_CLASSES[size]} ${className} ${canView ? 'cursor-pointer hover:opacity-90 transition-opacity' : ''}`}
        {...(canView
          ? {
              onClick: handleOpenViewer,
              onKeyDown: (event: React.KeyboardEvent) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  handleOpenViewer(event);
                }
              },
              role: 'button' as const,
              tabIndex: 0,
              'aria-label': `View ${displayName}'s profile photo`,
              title: 'View profile photo',
            }
          : {})}
      >
        {imageUrl && !imageError ? (
          <img
            src={imageUrl}
            alt={displayName}
            className="w-full h-full object-cover pointer-events-none"
            onError={() => setImageError(true)}
          />
        ) : (
          <span>{initial}</span>
        )}
      </div>

      {canView && (
        <ImageViewerModal
          open={viewerOpen}
          imageUrl={imageUrl ?? null}
          alt={`${displayName}'s profile photo`}
          title={displayName}
          onClose={() => setViewerOpen(false)}
          restrictSaving
        />
      )}
    </>
  );
};
