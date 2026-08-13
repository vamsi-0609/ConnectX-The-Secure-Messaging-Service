import React, { useEffect, useState } from 'react';
import { Loader2, ImageOff, ZoomIn } from 'lucide-react';
import { mediaApi } from '../../api/mediaApi';
import { ImageViewerModal } from '../common/ImageViewerModal';
import { saveImageToGallery } from '../../utils/saveMedia';

interface ImageMessageContentProps {
  mediaId?: number;
  mimeType?: string;
  localMediaUrl?: string;
  caption?: string;
  isSelf: boolean;
  formattedTime?: string;
  status?: React.ReactNode;
}

export const ImageMessageContent: React.FC<ImageMessageContentProps> = ({
  mediaId,
  mimeType,
  localMediaUrl,
  caption,
  isSelf,
  formattedTime,
  status,
}) => {
  const [imageUrl, setImageUrl] = useState<string | null>(localMediaUrl ?? null);
  const [loading, setLoading] = useState(!localMediaUrl && !!mediaId);
  const [error, setError] = useState(false);
  const [viewerOpen, setViewerOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (localMediaUrl) {
      setImageUrl(localMediaUrl);
      setLoading(false);
      setError(false);
      return;
    }

    if (!mediaId) {
      setLoading(false);
      setError(true);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(false);

    mediaApi
      .getMediaObjectUrl(mediaId)
      .then((url) => {
        if (!cancelled) {
          setImageUrl(url);
          setLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError(true);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [mediaId, localMediaUrl]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await saveImageToGallery({
        mediaId,
        localMediaUrl,
        mimeType,
        filenamePrefix: 'connectx-photo',
      });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to save image';
      alert(message);
    } finally {
      setSaving(false);
    }
  };

  const hasCaption = !!caption?.trim();

  return (
    <>
      <div className={hasCaption ? 'space-y-1.5' : undefined}>
        <button
          type="button"
          onClick={() => imageUrl && setViewerOpen(true)}
          disabled={!imageUrl}
          className="relative block overflow-hidden rounded-2xl disabled:cursor-default group/image"
          aria-label="Open image"
        >
          {loading ? (
            <div className="flex min-h-[140px] min-w-[180px] items-center justify-center rounded-2xl bg-slate-800/30">
              <Loader2 className="h-6 w-6 animate-spin text-slate-300" />
            </div>
          ) : error || !imageUrl ? (
            <div className="flex min-h-[140px] min-w-[180px] flex-col items-center justify-center gap-2 rounded-2xl bg-slate-800/30 px-4 text-xs text-rose-200">
              <ImageOff className="h-5 w-5" />
              Unable to load image
            </div>
          ) : (
            <>
              <img
                src={imageUrl}
                alt={caption || 'Shared image'}
                className="block max-h-80 max-w-full rounded-2xl object-cover"
                loading="lazy"
                draggable={false}
              />
              <span className="absolute inset-0 flex items-center justify-center rounded-2xl bg-black/0 transition-colors group-hover/image:bg-black/20">
                <span className="inline-flex items-center gap-1.5 rounded-full bg-black/55 px-3 py-1.5 text-xs font-medium text-white opacity-0 transition-opacity group-hover/image:opacity-100">
                  <ZoomIn className="h-3.5 w-3.5" />
                  View
                </span>
              </span>
              {!hasCaption && formattedTime && (
                <span className="pointer-events-none absolute bottom-2 right-2 inline-flex items-center gap-1 rounded-md bg-black/50 px-2 py-0.5 text-[10px] leading-none text-white/95 backdrop-blur-sm">
                  {formattedTime}
                  {status}
                </span>
              )}
            </>
          )}
        </button>

        {hasCaption && (
          <div
            className={`rounded-2xl px-3 py-2 shadow-sm ${
              isSelf
                ? 'bg-gradient-to-br from-indigo-600 to-violet-600 text-white'
                : 'bg-slate-800/95 text-slate-100 border border-slate-700/50'
            }`}
          >
            <p className="whitespace-pre-wrap break-words text-[13px] leading-snug md:text-[15px]">{caption}</p>
            {formattedTime && (
              <div className={`mt-1 flex items-center justify-end gap-1 ${isSelf ? 'text-indigo-100/80' : 'text-slate-400'}`}>
                <span className="text-[10px] leading-none md:text-[11px]">{formattedTime}</span>
                {status}
              </div>
            )}
          </div>
        )}
      </div>

      <ImageViewerModal
        open={viewerOpen}
        imageUrl={imageUrl}
        alt={caption || 'Shared image'}
        title={caption || 'Photo'}
        onClose={() => setViewerOpen(false)}
        onSave={handleSave}
        saving={saving}
      />
    </>
  );
};
