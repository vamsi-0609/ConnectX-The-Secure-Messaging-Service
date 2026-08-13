const THEME_STORAGE_KEY = 'connectx_theme';

export type ThemeMode = 'dark' | 'light';

export function getStoredTheme(): ThemeMode {
  const saved = localStorage.getItem(THEME_STORAGE_KEY);
  return saved === 'light' ? 'light' : 'dark';
}

export function applyTheme(isDarkMode: boolean): void {
  if (isDarkMode) {
    document.documentElement.classList.add('dark');
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
  } else {
    document.documentElement.classList.remove('dark');
    localStorage.setItem(THEME_STORAGE_KEY, 'light');
  }
}

export function isDarkTheme(): boolean {
  return getStoredTheme() === 'dark';
}
