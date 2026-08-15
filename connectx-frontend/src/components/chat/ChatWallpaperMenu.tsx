import React, { useRef, useState } from 'react';
import { ArrowLeft, Check, ImagePlus, Loader2, Ban } from 'lucide-react';
import {
  ChatWallpaperSetting,
  PRESET_WALLPAPERS,
  PresetWallpaperId,
} from '../../utils/chatWallpaper';
import { compressWallpaperImage } from '../../utils/chatWallpaperUpload';

interface ChatWallpaperMenuProps {
  selected: ChatWallpaperSetting;
  onSelect: (setting: ChatWallpaperSetting) => void;
  onBack: () => void;
}

export const ChatWallpaperMenu: React.FC<ChatWallpaperMenuProps> = ({
  selected,
  onSelect,
  onBack,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  const handleUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    setUploading(true);
    try {
      const dataUrl = await compressWallpaperImage(file);
      onSelect({ type: 'custom', dataUrl });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to use this image.';
      alert(message);
    } finally {
      setUploading(false);
    }
  };

  const noneSelected = selected.type === 'none';
  const customSelected = selected.type === 'custom';

  return (
    <div className="p-2 select-none">
      <button
        type="button"
        onClick={onBack}
        className="mb-2 flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800"
      >
        <ArrowLeft className="h-4 w-4" />
        Back
      </button>

      <p className="px-1 pb-2 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        Chat background
      </p>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        className="hidden"
        onChange={handleUpload}
      />

      <div className="max-h-72 space-y-2 overflow-y-auto px-1 pb-1">
        <div className="grid grid-cols-3 gap-2">
          <button
            type="button"
            onClick={() => onSelect({ type: 'none' })}
            className={`relative overflow-hidden rounded-xl border transition-colors ${
              noneSelected
                ? 'border-indigo-500 ring-2 ring-indigo-500/40'
                : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'
            }`}
            title="Default"
          >
            <div className="flex aspect-[4/5] flex-col items-center justify-center gap-1 bg-slate-100 dark:bg-slate-800">
              <Ban className="h-5 w-5 text-slate-400" />
              <span className="text-[10px] font-medium text-slate-500 dark:text-slate-300">Default</span>
            </div>
            {noneSelected && (
              <span className="absolute right-1.5 top-1.5 rounded-full bg-indigo-500 p-0.5 text-white">
                <Check className="h-3 w-3" strokeWidth={3} />
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className={`relative overflow-hidden rounded-xl border transition-colors ${
              customSelected
                ? 'border-indigo-500 ring-2 ring-indigo-500/40'
                : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'
            }`}
            title="Upload image"
          >
            <div className="relative flex aspect-[4/5] flex-col items-center justify-center gap-1 bg-slate-100 dark:bg-slate-800">
              {customSelected && selected.type === 'custom' ? (
                <img
                  src={selected.dataUrl}
                  alt="Custom wallpaper"
                  className="absolute inset-0 h-full w-full object-cover"
                />
              ) : uploading ? (
                <Loader2 className="h-5 w-5 animate-spin text-indigo-500" />
              ) : (
                <ImagePlus className="h-5 w-5 text-indigo-500" />
              )}
              <span className="relative z-10 rounded bg-black/45 px-1.5 py-0.5 text-[10px] font-medium text-white">
                {uploading ? 'Saving...' : 'Your photo'}
              </span>
            </div>
            {customSelected && !uploading && (
              <span className="absolute right-1.5 top-1.5 rounded-full bg-indigo-500 p-0.5 text-white">
                <Check className="h-3 w-3" strokeWidth={3} />
              </span>
            )}
          </button>

          {PRESET_WALLPAPERS.map((option) => {
            const isSelected =
              selected.type === 'preset' && selected.id === option.id;

            return (
              <button
                key={option.id}
                type="button"
                onClick={() => onSelect({ type: 'preset', id: option.id as PresetWallpaperId })}
                className={`relative overflow-hidden rounded-xl border transition-colors ${
                  isSelected
                    ? 'border-indigo-500 ring-2 ring-indigo-500/40'
                    : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'
                }`}
                title={option.name}
              >
                <img
                  src={option.src}
                  alt={option.name}
                  className="aspect-[4/5] h-full w-full object-cover"
                  loading="lazy"
                />
                <span className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent px-1.5 pb-1.5 pt-6 text-[10px] font-medium text-white">
                  {option.name}
                </span>
                {isSelected && (
                  <span className="absolute right-1.5 top-1.5 rounded-full bg-indigo-500 p-0.5 text-white">
                    <Check className="h-3 w-3" strokeWidth={3} />
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      <p className="px-1 pt-2 text-[10px] leading-relaxed text-slate-500 dark:text-slate-400">
        Background images stay faint so messages stay easy to read on mobile and desktop.
      </p>
    </div>
  );
};
