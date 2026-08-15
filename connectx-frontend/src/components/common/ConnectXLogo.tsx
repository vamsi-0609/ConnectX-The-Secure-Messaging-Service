import React from 'react';

/**
 * ConnectX canonical brand logos:
 * - Primary/Gradient (Logo 1): Vibrant purple-indigo squircle with clean white shield outline.
 * - Dark (Logo 2): Dark slate-navy squircle with lavender-indigo shield outline and checkmark.
 * - White / Muted: Pure stroke variants for custom container integration.
 */

export type LogoSize = 'sm' | 'md' | 'lg' | 'xl';
export type LogoVariant = 'gradient' | 'primary' | 'dark' | 'white' | 'muted';

interface ConnectXLogoProps {
  size?: LogoSize;
  variant?: LogoVariant;
  className?: string;
  /** When true, suppresses the glow/pulse animation */
  static?: boolean;
}

const SIZE_PX: Record<LogoSize, number> = {
  sm: 24,
  md: 32,
  lg: 40,
  xl: 48,
};

export const ConnectXLogo: React.FC<ConnectXLogoProps> = ({
  size = 'md',
  variant = 'gradient',
  className = '',
  static: isStatic = false,
}) => {
  const px = SIZE_PX[size];
  const isDarkVariant = variant === 'dark';
  const isWhiteOnly = variant === 'white';
  const isMuted = variant === 'muted';
  const gradId = `cx-logo-grad-${size}-${variant}`;

  return (
    <svg
      width={px}
      height={px}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-label="ConnectX"
      className={`flex-shrink-0 select-none ${!isStatic ? 'transition-transform duration-200 hover:scale-105' : ''} ${className}`}
    >
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#6366f1" />
          <stop offset="100%" stopColor="#7c3aed" />
        </linearGradient>
      </defs>

      {/* Squircle Background Container */}
      {!isWhiteOnly && !isMuted && (
        <rect
          width="32"
          height="32"
          rx="9"
          fill={isDarkVariant ? '#181e36' : `url(#${gradId})`}
          stroke={isDarkVariant ? 'rgba(99, 102, 241, 0.2)' : 'none'}
          strokeWidth="1"
        />
      )}

      {isDarkVariant ? (
        /* Logo 2: Dark Squircle + Shield with Checkmark */
        <g stroke="#93a5ff" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" fill="none">
          <path d="M16 25C16 25 23 21 23 15V8.5L16 6L9 8.5V15C9 21 16 25 16 25Z" />
          <path d="M13 15.5L15 17.5L19 13.5" strokeWidth="2" />
        </g>
      ) : (
        /* Logo 1: Vibrant Indigo/Purple Squircle + Clean White Shield Outline */
        <path
          d="M16 25C16 25 23 21 23 15V8.5L16 6L9 8.5V15C9 21 16 25 16 25Z"
          stroke={isWhiteOnly ? 'white' : isMuted ? 'currentColor' : 'white'}
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
          fill="none"
        />
      )}
    </svg>
  );
};
