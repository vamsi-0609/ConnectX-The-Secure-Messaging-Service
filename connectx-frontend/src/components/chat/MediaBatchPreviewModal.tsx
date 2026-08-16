import React, { useEffect, useMemo, useRef, useState } from 'react';
import { X, Image as ImageIcon, FileText, Plus, Send, Loader2, AlertCircle } from 'lucide-react';
import { MEDIA_IMAGE_ACCEPT, validateMediaImageFile } from '../../utils/mediaImage';

interface MediaBatchPreviewModalProps {
  initialImageFiles?: File[];
  initialDocFiles?: File[];
  onClose: () => void;
  onSendBatch: (images: File[], docs: File[]) => Promise<void>;
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

export const MediaBatchPreviewModal: React.FC<MediaBatchPreviewModalProps> = ({
  initialImageFiles = [],
  initialDocFiles = [],
  onClose,
  onSendBatch,
}) => {
  const [imageFiles, setImageFiles] = useState<File[]>(initialImageFiles);
  const [docFiles, setDocFiles] = useState<File[]>(initialDocFiles);
  const [sending, setSending] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const addImageRef = useRef<HTMLInputElement>(null);
  const addDocRef = useRef<HTMLInputElement>(null);

  // Recomputed only when the file list actually changes (not on every re-render,
  // e.g. the `sending` toggle) -- the matching cleanup below revokes the previous
  // batch of URLs the instant this one replaces it, and again on unmount.
  const imagePreviewUrls = useMemo(
    () => imageFiles.map((file) => URL.createObjectURL(file)),
    [imageFiles]
  );

  useEffect(() => {
    return () => {
      imagePreviewUrls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [imagePreviewUrls]);

  const handleRemoveImage = (index: number) => {
    setImageFiles((prev) => prev.filter((_, i) => i !== index));
    setErrorMsg(null);
  };

  const handleRemoveDoc = (index: number) => {
    setDocFiles((prev) => prev.filter((_, i) => i !== index));
    setErrorMsg(null);
  };

  const handleAddMoreImages = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    e.target.value = '';
    if (selected.length === 0) return;

    if (imageFiles.length + selected.length > 10) {
      setErrorMsg('Maximum 10 images can be sent at once.');
      return;
    }

    const validNewImages: File[] = [];
    for (const f of selected) {
      const err = validateMediaImageFile(f);
      if (err) {
        setErrorMsg(err);
        return;
      }
      validNewImages.push(f);
    }

    setErrorMsg(null);
    setImageFiles((prev) => [...prev, ...validNewImages]);
  };

  const handleAddMoreDocs = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    e.target.value = '';
    if (selected.length === 0) return;

    if (docFiles.length + selected.length > 5) {
      setErrorMsg('Maximum 5 documents can be sent at once.');
      return;
    }

    for (const f of selected) {
      if (f.size > 50 * 1024 * 1024) {
        setErrorMsg(`File "${f.name}" exceeds 50 MB limit.`);
        return;
      }
    }

    setErrorMsg(null);
    setDocFiles((prev) => [...prev, ...selected]);
  };

  const handleSubmit = async () => {
    if (imageFiles.length === 0 && docFiles.length === 0) {
      onClose();
      return;
    }
    if (imageFiles.length > 10) {
      setErrorMsg('Maximum 10 images can be sent at once.');
      return;
    }
    if (docFiles.length > 5) {
      setErrorMsg('Maximum 5 documents can be sent at once.');
      return;
    }

    setSending(true);
    setErrorMsg(null);

    try {
      await onSendBatch(imageFiles, docFiles);
      onClose();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to send media batch';
      setErrorMsg(msg);
    } finally {
      setSending(false);
    }
  };

  const totalFiles = imageFiles.length + docFiles.length;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm select-none" onClick={onClose}>
      <div
        className="w-full max-w-lg bg-slate-900 border border-slate-800 rounded-3xl p-6 shadow-2xl space-y-5 animate-pop-in"
        onClick={(e) => e.stopPropagation()}
      >
        <input
          ref={addImageRef}
          type="file"
          accept={MEDIA_IMAGE_ACCEPT}
          multiple
          className="hidden"
          onChange={handleAddMoreImages}
        />
        <input
          ref={addDocRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleAddMoreDocs}
        />

        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-lg font-bold text-white tracking-tight">Preview Attachment Batch</h3>
            <p className="text-xs text-slate-400 mt-0.5">
              Review files before sending. Max 10 images, max 5 documents.
            </p>
          </div>
          <button
            onClick={onClose}
            disabled={sending}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {errorMsg && (
          <div className="flex items-center gap-2 p-3 rounded-2xl bg-rose-500/10 border border-rose-500/30 text-rose-400 text-xs font-semibold">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{errorMsg}</span>
          </div>
        )}

        <div className="max-h-80 overflow-y-auto space-y-4 pr-1">
          {/* Images Section */}
          {imageFiles.length > 0 && (
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
                  <ImageIcon className="w-3.5 h-3.5 text-indigo-400" />
                  Images ({imageFiles.length}/10)
                </span>
                {imageFiles.length < 10 && (
                  <button
                    type="button"
                    onClick={() => addImageRef.current?.click()}
                    disabled={sending}
                    className="text-xs font-medium text-indigo-400 hover:text-indigo-300 inline-flex items-center gap-1"
                  >
                    <Plus className="w-3.5 h-3.5" /> Add Images
                  </button>
                )}
              </div>

              <div className="grid grid-cols-3 gap-2">
                {imageFiles.map((file, idx) => {
                  const previewUrl = imagePreviewUrls[idx];
                  return (
                    <div key={`img-${idx}-${file.name}`} className="relative aspect-square rounded-xl overflow-hidden group border border-slate-700 bg-slate-800">
                      <img src={previewUrl} alt={file.name} className="w-full h-full object-cover" />
                      <button
                        type="button"
                        onClick={() => handleRemoveImage(idx)}
                        disabled={sending}
                        className="absolute top-1 right-1 p-1 rounded-full bg-black/60 text-white hover:bg-rose-600 opacity-90 transition-opacity"
                        title="Remove image"
                      >
                        <X className="w-3.5 h-3.5" />
                      </button>
                      <div className="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 to-transparent p-1 text-[10px] text-white truncate font-mono">
                        {formatBytes(file.size)}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Documents Section */}
          {docFiles.length > 0 && (
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
                  <FileText className="w-3.5 h-3.5 text-blue-400" />
                  Documents ({docFiles.length}/5)
                </span>
                {docFiles.length < 5 && (
                  <button
                    type="button"
                    onClick={() => addDocRef.current?.click()}
                    disabled={sending}
                    className="text-xs font-medium text-indigo-400 hover:text-indigo-300 inline-flex items-center gap-1"
                  >
                    <Plus className="w-3.5 h-3.5" /> Add Docs
                  </button>
                )}
              </div>

              <div className="space-y-2">
                {docFiles.map((file, idx) => (
                  <div key={`doc-${idx}-${file.name}`} className="flex items-center justify-between p-3 rounded-xl bg-slate-800 border border-slate-700">
                    <div className="flex items-center gap-3 min-w-0 pr-2">
                      <FileText className="w-5 h-5 text-blue-400 flex-shrink-0" />
                      <div className="min-w-0">
                        <p className="text-xs font-semibold text-white truncate" title={file.name}>
                          {file.name}
                        </p>
                        <p className="text-[10px] text-slate-400 font-mono">{formatBytes(file.size)}</p>
                      </div>
                    </div>

                    <button
                      type="button"
                      onClick={() => handleRemoveDoc(idx)}
                      disabled={sending}
                      className="p-1 rounded-lg text-slate-400 hover:text-rose-400 hover:bg-slate-700/60"
                      title="Remove document"
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-800">
          <button
            type="button"
            onClick={onClose}
            disabled={sending}
            className="px-4 py-2.5 rounded-xl text-xs font-semibold text-slate-400 hover:bg-slate-800 hover:text-white"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={sending || totalFiles === 0}
            className="px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold inline-flex items-center gap-2 shadow-lg shadow-indigo-600/30 disabled:opacity-50"
          >
            {sending ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                Sending {totalFiles} Files...
              </>
            ) : (
              <>
                <Send className="w-4 h-4" />
                Send {totalFiles} {totalFiles === 1 ? 'File' : 'Files'}
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
