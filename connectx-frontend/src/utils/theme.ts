const THEME_STORAGE_KEY = 'connectx_theme';

export type ThemeMode = 'dark' | 'light';

export function getStoredTheme(): ThemeMode {
  const saved = localStorage.getItem(THEME_STORAGE_KEY);
  return saved === 'light' ? 'light' : 'dark';
}

// Same values index.html's pre-paint inline script and App-shell background classes
// (bg-slate-100 dark:bg-[#090d16]) already use -- the single source of truth for
// "what color is the outermost ConnectX shell," not a new/arbitrary pair of colors.
const THEME_COLOR_DARK = '#090d16';
const THEME_COLOR_LIGHT = '#f1f5f9';

function syncThemeColorMeta(isDarkMode: boolean): void {
  const meta = document.querySelector('meta[name="theme-color"]');
  meta?.setAttribute('content', isDarkMode ? THEME_COLOR_DARK : THEME_COLOR_LIGHT);
}

export function applyTheme(isDarkMode: boolean): void {
  if (isDarkMode) {
    document.documentElement.classList.add('dark');
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
  } else {
    document.documentElement.classList.remove('dark');
    localStorage.setItem(THEME_STORAGE_KEY, 'light');
  }
  // Keeps the Android/browser system status bar (and, in supporting standalone/
  // edge-to-edge contexts, the navigation bar) following the same theme as the
  // app shell -- previously a static brand-purple value, the entire cause of the
  // disconnected violet system bar this fixes.
  syncThemeColorMeta(isDarkMode);
}

export function isDarkTheme(): boolean {
  return getStoredTheme() === 'dark';
}
