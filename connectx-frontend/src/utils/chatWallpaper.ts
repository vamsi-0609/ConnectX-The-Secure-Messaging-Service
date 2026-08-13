export type PresetWallpaperId =
  | 'aurora'
  | 'ocean'
  | 'forest'
  | 'sunset'
  | 'minimal'
  | 'bloom'
  | 'night';

export type ChatWallpaperSetting =
  | { type: 'none' }
  | { type: 'preset'; id: PresetWallpaperId }
  | { type: 'custom'; dataUrl: string };

export interface PresetWallpaperOption {
  id: PresetWallpaperId;
  name: string;
  src: string;
}

const STORAGE_KEY = 'connectx_chat_wallpapers_v2';

export const PRESET_WALLPAPERS: PresetWallpaperOption[] = [
  { id: 'aurora', name: 'Aurora', src: '/wallpapers/aurora.svg' },
  { id: 'ocean', name: 'Ocean', src: '/wallpapers/ocean.svg' },
  { id: 'forest', name: 'Forest', src: '/wallpapers/forest.svg' },
  { id: 'sunset', name: 'Sunset', src: '/wallpapers/sunset.svg' },
  { id: 'minimal', name: 'Minimal', src: '/wallpapers/minimal.svg' },
  { id: 'bloom', name: 'Bloom', src: '/wallpapers/bloom.svg' },
  { id: 'night', name: 'Night', src: '/wallpapers/night.svg' },
];

const PRESET_IDS = new Set(PRESET_WALLPAPERS.map((item) => item.id));

function readWallpaperMap(): Record<string, ChatWallpaperSetting> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, ChatWallpaperSetting>;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function normalizeSetting(value: unknown): ChatWallpaperSetting {
  if (!value || typeof value !== 'object') {
    return { type: 'none' };
  }

  const setting = value as ChatWallpaperSetting;

  if (setting.type === 'preset' && PRESET_IDS.has(setting.id)) {
    return setting;
  }

  if (setting.type === 'custom' && typeof setting.dataUrl === 'string' && setting.dataUrl.startsWith('data:image/')) {
    return { type: 'custom', dataUrl: setting.dataUrl };
  }

  return { type: 'none' };
}

export function getConversationWallpaper(conversationId: number): ChatWallpaperSetting {
  const map = readWallpaperMap();
  return normalizeSetting(map[String(conversationId)]);
}

export function setConversationWallpaper(conversationId: number, setting: ChatWallpaperSetting): void {
  const map = readWallpaperMap();
  map[String(conversationId)] = normalizeSetting(setting);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
}

export function getWallpaperImageUrl(setting: ChatWallpaperSetting): string | null {
  if (setting.type === 'preset') {
    return PRESET_WALLPAPERS.find((item) => item.id === setting.id)?.src ?? null;
  }

  if (setting.type === 'custom') {
    return setting.dataUrl;
  }

  return null;
}

export function isSameWallpaper(a: ChatWallpaperSetting, b: ChatWallpaperSetting): boolean {
  if (a.type !== b.type) return false;
  if (a.type === 'none') return true;
  if (a.type === 'preset' && b.type === 'preset') return a.id === b.id;
  if (a.type === 'custom' && b.type === 'custom') return a.dataUrl === b.dataUrl;
  return false;
}

export function getWallpaperLabel(setting: ChatWallpaperSetting): string {
  if (setting.type === 'none') return 'Default';
  if (setting.type === 'preset') {
    return PRESET_WALLPAPERS.find((item) => item.id === setting.id)?.name ?? 'Preset';
  }
  return 'Your photo';
}
