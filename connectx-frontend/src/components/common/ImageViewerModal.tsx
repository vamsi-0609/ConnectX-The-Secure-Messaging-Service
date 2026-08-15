import React, { useEffect, useState } from 'react';
import { X, Download, Loader2, Check, ShieldCheck } from 'lucide-react';

interface ImageViewerModalProps {
  open: boolean;
  imageUrl: string | null;
  alt?: string;
  title?: string;
  onClose: () => void;
  onSave?: () => Promise<void>;
  saving?: boolean;
  /** Profile photos: hides Save, blocks right-click/drag-out as a deterrent. */
  restrictSaving?: boolean;
}

export const ImageViewerModal: React.FC<ImageViewerModalProps> = ({
  open,
  imageUrl,
  alt = 'Image',
  title,
  onClose,
  onSave,
  saving = false,
  restrictSaving = false,
}) => {
  const [justSaved, setJustSaved] = useState(false);

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

  useEffect(() => {
    if (!open) setJustSaved(false);
  }, [open]);

  const handleSaveClick = async () => {
    if (!onSave || saving || justSaved) return;
    try {
      await onSave();
      setJustSaved(true);
      window.setTimeout(() => setJustSaved(false), 1500);
    } catch {
      // Parent surfaces the error (e.g. via alert); nothing further to do here.
    }
  };

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
          {restrictSaving ? (
            <span
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl bg-white/5 text-white/50 text-xs font-medium select-none"
              title="Profile photos are protected and can't be saved"
            >
              <ShieldCheck className="w-3.5 h-3.5" />
              Protected
            </span>
          ) : (
            onSave && (
              <button
                type="button"
                onClick={() => void handleSaveClick()}
                disabled={saving || justSaved}
                className="inline-flex items-center gap-2 px-3 py-2 rounded-xl bg-white/10 hover:bg-white/15 text-white text-sm font-medium disabled:opacity-90 transition-colors"
              >
                {saving ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : justSaved ? (
                  <Check className="w-4 h-4 text-emerald-400" />
                ) : (
                  <Download className="w-4 h-4" />
                )}
                <span className={justSaved ? 'text-emerald-400' : undefined}>
                  {justSaved ? 'Saved' : 'Save to gallery'}
                </span>
              </button>
            )
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
          onContextMenu={restrictSaving ? (e) => e.preventDefault() : undefined}
        />
      </div>
    </div>
  );
};
