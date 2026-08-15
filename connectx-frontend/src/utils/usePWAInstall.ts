import { useState, useEffect } from 'react';

interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[];
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
  prompt(): Promise<void>;
}

export type PWAInstallState =
  | 'checking'       // Still determining state
  | 'installed'      // Already running as installed PWA
  | 'available'      // Browser exposed install prompt (Chrome/Edge/Android)
  | 'ios'            // iOS Safari — no prompt, show manual instructions
  | 'unavailable';   // Browser doesn't support installation at all

const DISMISSED_KEY = 'connectx_pwa_banner_dismissed';

interface UsePWAInstallResult {
  state: PWAInstallState;
  install: () => Promise<boolean>;
  resetDismissed: () => void;
}

export function usePWAInstall(): UsePWAInstallResult {
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [state, setState] = useState<PWAInstallState>('checking');

  useEffect(() => {
    // Already running standalone (installed)
    const isStandalone =
      window.matchMedia('(display-mode: standalone)').matches ||
      (navigator as any).standalone === true;

    if (isStandalone) {
      setState('installed');
      return;
    }

    // iOS Safari detection (no beforeinstallprompt)
    const ua = navigator.userAgent;
    const isIos = /iphone|ipad|ipod/i.test(ua) && !('MSStream' in window);
    if (isIos) {
      setState('ios');
      return;
    }

    // Wait for Chromium install prompt
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
      setState('available');
    };

    window.addEventListener('beforeinstallprompt', handler);

    // After a short settle period, if no prompt arrived, mark unavailable
    const timer = setTimeout(() => {
      setState((prev) => (prev === 'checking' ? 'unavailable' : prev));
    }, 1500);

    return () => {
      window.removeEventListener('beforeinstallprompt', handler);
      clearTimeout(timer);
    };
  }, []);

  const install = async (): Promise<boolean> => {
    if (!deferredPrompt) return false;
    try {
      await deferredPrompt.prompt();
      const { outcome } = await deferredPrompt.userChoice;
      if (outcome === 'accepted') {
        setState('installed');
        setDeferredPrompt(null);
        return true;
      }
    } catch {
      // prompt() can throw if called outside a user gesture — ignore silently
    }
    return false;
  };

  const resetDismissed = () => {
    localStorage.removeItem(DISMISSED_KEY);
  };

  return { state, install, resetDismissed };
}
