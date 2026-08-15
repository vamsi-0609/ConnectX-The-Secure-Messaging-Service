import React, { useEffect } from 'react';
import { X, Download, Loader2 } from 'lucide-react';

interface ImageViewerModalProps {
  open: boolean;
  imageUrl: string | null;
  alt?: string;
  title?: string;
  onClose: () => void;
  onSave?: () => Promise<void>;
  saving?: boolean;
}

export const ImageViewerModal: React.FC<ImageViewerModalProps> = ({
  open,
  imageUrl,
  alt = 'Image',
  title,
  onClose,
  onSave,
  saving = false,
}) => {
  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };

    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      document.body.style.overflow = '';
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [open, onClose]);

  if (!open || !imageUrl) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-[80] flex flex-col bg-black/95 backdrop-blur-sm select-none"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={title || alt}
    >
      <div className="flex items-center justify-between px-4 py-3 border-b border-white/10" onClick={(e) => e.stopPropagation()}>
        <div className="min-w-0">
          {title && <h3 className="text-sm font-medium text-white truncate">{title}</h3>}
        </div>
        <div className="flex items-center gap-2">
          {onSave && (
            <button
              type="button"
              onClick={() => void onSave()}
              disabled={saving}
              className="inline-flex items-center gap-2 px-3 py-2 rounded-xl bg-white/10 hover:bg-white/15 text-white text-sm font-medium disabled:opacity-60"
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
              Save to gallery
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-xl text-white/80 hover:text-white hover:bg-white/10"
            aria-label="Close image viewer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-0 flex items-center justify-center p-4" onClick={(e) => e.stopPropagation()}>
        <img
          src={imageUrl}
          alt={alt}
          className="max-w-full max-h-full object-contain rounded-lg shadow-2xl"
          draggable={false}
        />
      </div>
    </div>
  );
};
