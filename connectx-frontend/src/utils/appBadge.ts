// Reflects the app's unread count on the OS taskbar/dock icon via the
// App Badging API. Support is limited to installed PWAs on Chromium-based
// browsers, so every call is feature-detected and best-effort.
export const appBadge = {
  isSupported(): boolean {
    return typeof navigator !== 'undefined' && 'setAppBadge' in navigator;
  },

  async set(count: number): Promise<void> {
    if (!this.isSupported()) return;
    try {
      if (count > 0) {
        await navigator.setAppBadge(count);
      } else {
        await navigator.clearAppBadge();
      }
    } catch {
      // Badging can throw in unsupported/unfocused contexts — safe to ignore.
    }
  },

  async clear(): Promise<void> {
    if (!this.isSupported()) return;
    try {
      await navigator.clearAppBadge();
    } catch {
      // Ignore — see set().
    }
  },
};
