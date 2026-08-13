import React, { useState } from 'react';
import { Loader2, FileText, Download, FileArchive, File as FileIcon } from 'lucide-react';
import { mediaApi } from '../../api/mediaApi';

interface DocumentMessageContentProps {
  mediaId?: number;
  mimeType?: string;
  fileSizeBytes?: number;
  caption?: string; // Storing the filename in caption
  isSelf: boolean;
  formattedTime?: string;
  status?: React.ReactNode;
}

function formatBytes(bytes?: number): string {
  if (bytes == null || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function getFileIcon(mimeType?: string, filename?: string) {
  const name = (filename || '').toLowerCase();
  const type = (mimeType || '').toLowerCase();
  if (type.includes('pdf') || name.endsWith('.pdf')) {
    return <FileText className="h-8 w-8 text-rose-400" />;
  }
  if (type.includes('zip') || type.includes('tar') || type.includes('rar') || name.endsWith('.zip') || name.endsWith('.rar') || name.endsWith('.7z')) {
    return <FileArchive className="h-8 w-8 text-amber-400" />;
  }
  if (type.includes('word') || type.includes('office') || name.endsWith('.doc') || name.endsWith('.docx')) {
    return <FileText className="h-8 w-8 text-blue-400" />;
  }
  if (type.includes('excel') || type.includes('sheet') || name.endsWith('.xls') || name.endsWith('.xlsx')) {
    return <FileText className="h-8 w-8 text-emerald-400" />;
  }
  return <FileIcon className="h-8 w-8 text-indigo-400" />;
}

export const DocumentMessageContent: React.FC<DocumentMessageContentProps> = ({
  mediaId,
  mimeType,
  fileSizeBytes,
  caption,
  isSelf,
  formattedTime,
  status,
}) => {
  const [loading, setLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const filename = caption || 'Document';

  const handleOpen = async () => {
    if (!mediaId || loading || downloading) return;
    setLoading(true);
    try {
      const url = await mediaApi.getMediaObjectUrl(mediaId);
      window.open(url, '_blank');
    } catch (err) {
      alert('Failed to open document');
    } finally {
      setLoading(false);
    }
  };

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!mediaId || loading || downloading) return;
    setDownloading(true);
    try {
      const blob = await mediaApi.getMediaBlob(mediaId);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    } catch (err) {
      alert('Failed to download document');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div
      onClick={handleOpen}
      className={`flex flex-col p-3 rounded-2xl border cursor-pointer select-none transition-all active:scale-[0.98] min-w-[220px] max-w-[280px] ${
        isSelf
          ? 'bg-gradient-to-br from-indigo-600 to-violet-600 text-white border-indigo-500/20'
          : 'bg-slate-800/95 border border-slate-700/50 text-slate-100'
      }`}
    >
      <div className="flex items-center gap-3">
        <div className="flex-shrink-0">
          {loading ? (
            <Loader2 className="h-8 w-8 animate-spin text-indigo-400" />
          ) : (
            getFileIcon(mimeType, filename)
          )}
        </div>

        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium truncate pr-2" title={filename}>
            {filename}
          </p>
          <p className={`text-[10px] mt-0.5 font-mono ${isSelf ? 'text-indigo-200/95' : 'text-slate-400/95'}`}>
            {formatBytes(fileSizeBytes)}
          </p>
        </div>

        <div className="flex-shrink-0">
          <button
            type="button"
            onClick={handleDownload}
            disabled={downloading}
            className={`p-2 rounded-lg hover:bg-black/15 transition-colors disabled:opacity-50 ${
              isSelf ? 'text-indigo-200 hover:text-white' : 'text-slate-400 hover:text-white'
            }`}
            title="Download document"
          >
            {downloading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Download className="h-4 w-4" />
            )}
          </button>
        </div>
      </div>

      <div className={`mt-2 flex items-center justify-end gap-1 ${isSelf ? 'text-indigo-200/80' : 'text-slate-400'}`}>
        <span className="text-[10px] leading-none">{formattedTime}</span>
        {status}
      </div>
    </div>
  );
};
