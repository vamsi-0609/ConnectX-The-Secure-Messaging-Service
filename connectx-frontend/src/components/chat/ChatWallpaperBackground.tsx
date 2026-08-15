import React from 'react';

interface ChatWallpaperBackgroundProps {
  imageUrl: string | null;
  isDarkMode: boolean;
}

export const ChatWallpaperBackground: React.FC<ChatWallpaperBackgroundProps> = ({
  imageUrl,
  isDarkMode,
}) => {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      {/* Base layer: uses base theme background color, and adds a nice default wallpaper pattern if no custom wallpaper image is selected */}
      <div
        className={`absolute inset-0 ${isDarkMode ? 'bg-[#0b101d]' : 'bg-[#f8fafc]'} ${
          !imageUrl ? 'chat-wallpaper' : ''
        }`}
      />

      {/* Wallpaper image layer (renders only when preset or custom wallpaper is chosen) */}
      {imageUrl && (
        <div
          className="absolute inset-0 bg-cover bg-center bg-no-repeat transition-opacity duration-300"
          style={{
            backgroundImage: `url("${imageUrl}")`,
            opacity: isDarkMode ? 0.38 : 0.28,
          }}
        />
      )}
    </div>
  );
};
