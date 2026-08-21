import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
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
  const [privacyHidden, setPrivacyHidden] = useState(false);

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

  // Browsers offer no API to block a real OS-level screenshot -- this can only
  // blur the photo when the app is backgrounded/loses focus, which deters the
  // task-switcher/app-preview snapshot leaking it, not an in-app screenshot.
  useEffect(() => {
    if (!open || !restrictSaving) return;

    const handleVisibilityChange = () => setPrivacyHidden(document.hidden);
    const handleBlur = () => setPrivacyHidden(true);
    const handleFocus = () => setPrivacyHidden(false);

    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('blur', handleBlur);
    window.addEventListener('focus', handleFocus);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('blur', handleBlur);
      window.removeEventListener('focus', handleFocus);
      setPrivacyHidden(false);
    };
  }, [open, restrictSaving]);

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

  // Portaled straight to document.body -- this modal is used both standalone (ProfileModal) and
  // nested inside a chat message bubble (ImageMessageContent, itself inside MessageBubble).
  // MessageBubble attaches its own onPointerDown/onPointerUp/onClick handlers (for the
  // long-press-to-select-message gesture) to the bubble's wrapping <div>; without a portal, this
  // modal renders as a normal DOM/React-tree descendant of that div, so every tap inside it --
  // Download included -- still bubbles through those handlers even though `fixed` positioning
  // visually detaches it from the bubble on screen. That's what made Download unreachable on a
  // real device specifically for chat images (nested inside a bubble) while working fine for
  // ProfileModal's copy (a standalone top-level modal with no such ancestor). Portaling removes
  // the nesting entirely, matching the pattern MessageBubble already uses for its own popups.
  return createPortal(
    <div
      className="fixed inset-0 z-[80] flex flex-col bg-black/95 backdrop-blur-sm select-none"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={title || alt}
    >
      <div
        className="image-viewer-topbar flex items-center justify-between px-4 py-3 border-b border-white/10 relative z-10"
        onClick={(e) => e.stopPropagation()}
      >
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
                aria-label="Download image"
                // Resting-state contrast raised (bg-white/15, not /10) and an explicit active:
                // state added -- the previous bg-white/10 with only a hover: state to darken
                // further relied on a hover affordance touch devices never trigger, making the
                // button easy to miss at rest on a phone.
                className="inline-flex items-center gap-2 px-3 py-2 rounded-xl bg-white/15 hover:bg-white/20 active:bg-white/25 text-white text-sm font-semibold disabled:opacity-90 transition-colors"
              >
                {saving ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : justSaved ? (
                  <Check className="w-4 h-4 text-emerald-400" />
                ) : (
                  <Download className="w-4 h-4" />
                )}
                <span className={justSaved ? 'text-emerald-400' : undefined}>
                  {justSaved ? 'Saved' : 'Download'}
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

      <div className="flex-1 min-h-0 flex items-center justify-center p-4 relative" onClick={(e) => e.stopPropagation()}>
        <img
          src={imageUrl}
          alt={alt}
          className={`max-w-full max-h-full object-contain rounded-lg shadow-2xl transition-[filter] duration-200 ${
            privacyHidden ? 'blur-2xl scale-105' : ''
          }`}
          draggable={false}
          onContextMenu={restrictSaving ? (e) => e.preventDefault() : undefined}
        />
        {privacyHidden && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/70 rounded-lg">
            <span className="flex items-center gap-2 text-white/80 text-sm font-medium">
              <ShieldCheck className="w-4 h-4" />
              Hidden for privacy
            </span>
          </div>
        )}
      </div>
    </div>,
    document.body
  );
};
