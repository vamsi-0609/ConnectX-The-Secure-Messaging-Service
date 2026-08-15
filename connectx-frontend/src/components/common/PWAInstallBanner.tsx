import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import { ConnectXLogo } from './ConnectXLogo';
import { usePWAInstall } from '../../utils/usePWAInstall';

const SESSION_SHOWN_KEY = 'connectx_pwa_session_shown';

/**
 * Subtle once-per-session install nudge — slides up from bottom.
 * Only shown for Chrome/Edge (available) and iOS (ios) states.
 * Once dismissed it won't re-appear until next browser session.
 * Profile → Install ConnectX is the intentional access path.
 */
export const PWAInstallBanner: React.FC = () => {
  const { state, install } = usePWAInstall();
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (state === 'checking' || state === 'installed' || state === 'unavailable') return;
    if (sessionStorage.getItem(SESSION_SHOWN_KEY)) return;

    // Small delay so the app loads first
    const t = setTimeout(() => setVisible(true), 4000);
    return () => clearTimeout(t);
  }, [state]);

  if (!visible) return null;

  const handleInstall = async () => {
    const accepted = await install();
    if (accepted) setVisible(false);
    markShown();
  };

  const handleDismiss = () => {
    setVisible(false);
    markShown();
  };

  function markShown() {
    sessionStorage.setItem(SESSION_SHOWN_KEY, '1');
  }

  return (
    <div
      className="
        fixed bottom-4 left-1/2 -translate-x-1/2 z-50
        flex items-center gap-3
        px-4 py-3 rounded-2xl
        bg-[#1a1f2e]/95 backdrop-blur-xl
        border border-indigo-500/25
        shadow-[0_8px_32px_rgba(99,102,241,0.2)]
        max-w-sm w-[calc(100%-2rem)]
        animate-slide-up
      "
      role="banner"
      aria-label="Install ConnectX app"
    >
      <ConnectXLogo size="md" variant="gradient" static className="flex-shrink-0" />

      <div className="flex-1 min-w-0">
        <p className="text-white text-sm font-semibold leading-tight">Install ConnectX</p>
        {state === 'ios' ? (
          <p className="text-slate-400 text-xs mt-0.5">
            Tap <strong className="text-slate-200">Share</strong> →{' '}
            <strong className="text-slate-200">Add to Home Screen</strong>
          </p>
        ) : (
          <p className="text-slate-400 text-xs mt-0.5">Add to home screen for quick access</p>
        )}
      </div>

      {state === 'available' && (
        <button
          onClick={handleInstall}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white text-xs font-semibold transition-colors flex-shrink-0"
          aria-label="Install app"
        >
          Install
        </button>
      )}

      <button
        onClick={handleDismiss}
        className="p-1 rounded-lg flex-shrink-0 text-slate-400 hover:text-white hover:bg-white/10 transition-colors"
        aria-label="Dismiss install banner"
      >
        <X className="w-4 h-4" />
      </button>
    </div>
  );
};
