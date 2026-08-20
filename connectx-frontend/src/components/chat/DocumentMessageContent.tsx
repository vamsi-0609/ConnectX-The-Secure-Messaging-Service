import React, { useState } from 'react';
import { Loader2, FileText, Download, ExternalLink, FileArchive, File as FileIcon } from 'lucide-react';
import { mediaApi } from '../../api/mediaApi';
import { resolveGroupMediaKey } from '../../crypto/groupMediaKey';
import { decryptBytesWithGroupKey } from '../../crypto/groupCrypto';
import { Group } from '../../types';

interface DocumentMessageContentProps {
  mediaId?: number;
  mimeType?: string;
  fileSizeBytes?: number;
  caption?: string; // Storing the filename in caption
  isSelf: boolean;
  formattedTime?: string;
  status?: React.ReactNode;
  // GROUP E2EE media only -- see ImageMessageContent's identical props for the full contract.
  group?: Group | null;
  currentUserId?: number;
  mediaGroupKeyVersion?: number;
  mediaNonce?: string;
}

function formatBytes(bytes?: number): string {
  if (bytes == null || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function getFileBadge(mimeType?: string, filename?: string) {
  const name = (filename || '').toLowerCase();
  const type = (mimeType || '').toLowerCase();
  if (type.includes('pdf') || name.endsWith('.pdf')) {
    return { icon: <FileText className="h-8 w-8 text-rose-400" />, label: 'PDF DOCUMENT', badgeBg: 'bg-rose-500/15 text-rose-300 border-rose-500/30' };
  }
  if (type.includes('zip') || type.includes('tar') || type.includes('rar') || name.endsWith('.zip') || name.endsWith('.rar') || name.endsWith('.7z')) {
    return { icon: <FileArchive className="h-8 w-8 text-amber-400" />, label: 'ARCHIVE', badgeBg: 'bg-amber-500/15 text-amber-300 border-amber-500/30' };
  }
  if (type.includes('word') || type.includes('office') || name.endsWith('.doc') || name.endsWith('.docx')) {
    return { icon: <FileText className="h-8 w-8 text-blue-400" />, label: 'WORD DOCUMENT', badgeBg: 'bg-blue-500/15 text-blue-300 border-blue-500/30' };
  }
  if (type.includes('excel') || type.includes('sheet') || name.endsWith('.xls') || name.endsWith('.xlsx')) {
    return { icon: <FileText className="h-8 w-8 text-emerald-400" />, label: 'SPREADSHEET', badgeBg: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30' };
  }
  return { icon: <FileIcon className="h-8 w-8 text-indigo-400" />, label: 'DOCUMENT', badgeBg: 'bg-indigo-500/15 text-indigo-300 border-indigo-500/30' };
}

export const DocumentMessageContent: React.FC<DocumentMessageContentProps> = ({
  mediaId,
  mimeType,
  fileSizeBytes,
  caption,
  isSelf,
  formattedTime,
  status,
  group,
  currentUserId,
  mediaGroupKeyVersion,
  mediaNonce,
}) => {
  const [loading, setLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const filename = caption || 'Document';
  const badgeInfo = getFileBadge(mimeType, filename);
  const isEncrypted = !!(group && currentUserId != null && mediaGroupKeyVersion != null && mediaNonce);

  const getDecryptedBlob = async (): Promise<Blob> => {
    const rawBlob = await mediaApi.getMediaBlob(mediaId!);
    if (!isEncrypted) {
      return rawBlob;
    }
    const groupKey = await resolveGroupMediaKey(group!, mediaGroupKeyVersion!, currentUserId!);
    if (!groupKey) {
      throw new Error('GROUP_KEY_UNAVAILABLE');
    }
    const ciphertext = await rawBlob.arrayBuffer();
    const decryptedBytes = await decryptBytesWithGroupKey(groupKey, ciphertext, mediaNonce!);
    return new Blob([decryptedBytes], { type: mimeType || 'application/octet-stream' });
  };

  const handleOpen = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!mediaId || loading || downloading) return;
    setLoading(true);
    try {
      const url = isEncrypted
        ? await mediaApi.getDecryptedGroupMediaObjectUrl(mediaId, mimeType || 'application/octet-stream', async (ciphertext) => {
            const groupKey = await resolveGroupMediaKey(group!, mediaGroupKeyVersion!, currentUserId!);
            if (!groupKey) {
              throw new Error('GROUP_KEY_UNAVAILABLE');
            }
            return decryptBytesWithGroupKey(groupKey, ciphertext, mediaNonce!);
          })
        : await mediaApi.getMediaObjectUrl(mediaId);
      window.open(url, '_blank');
    } catch {
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
      const blob = await getDecryptedBlob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    } catch {
      alert('Failed to download document');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div
      className={`flex flex-col p-3 rounded-2xl border select-none transition-all shadow-md min-w-[240px] max-w-[300px] ${
        isSelf
          ? 'bg-gradient-to-br from-indigo-600 to-violet-600 text-white border-indigo-400/30'
          : 'bg-slate-800/95 border border-slate-700/60 text-slate-100'
      }`}
    >
      <div className="flex items-start gap-3">
        <div className="flex-shrink-0 p-2 rounded-xl bg-slate-900/40 border border-slate-700/30">
          {loading ? <Loader2 className="h-8 w-8 animate-spin text-indigo-300" /> : badgeInfo.icon}
        </div>

        <div className="flex-1 min-w-0">
          <div className={`inline-block px-2 py-0.5 rounded text-[9px] font-semibold tracking-wider border mb-1 ${badgeInfo.badgeBg}`}>
            {badgeInfo.label}
          </div>
          <p className="text-sm font-semibold truncate pr-1" title={filename}>
            {filename}
          </p>
          <p className={`text-[11px] font-mono mt-0.5 ${isSelf ? 'text-indigo-200/90' : 'text-slate-400'}`}>
            {formatBytes(fileSizeBytes)}
          </p>
        </div>
      </div>

      <div className="mt-3 pt-2.5 border-t border-white/10 flex items-center justify-between gap-2">
        <button
          type="button"
          onClick={handleOpen}
          disabled={loading || downloading}
          className={`flex-1 py-1.5 px-3 rounded-lg text-xs font-semibold inline-flex items-center justify-center gap-1.5 transition-colors ${
            isSelf
              ? 'bg-white/15 hover:bg-white/25 text-white'
              : 'bg-slate-700/80 hover:bg-slate-700 text-slate-100'
          } disabled:opacity-50`}
        >
          {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ExternalLink className="h-3.5 w-3.5" />}
          Open
        </button>

        <button
          type="button"
          onClick={handleDownload}
          disabled={loading || downloading}
          className={`flex-1 py-1.5 px-3 rounded-lg text-xs font-semibold inline-flex items-center justify-center gap-1.5 transition-colors ${
            isSelf
              ? 'bg-white/15 hover:bg-white/25 text-white'
              : 'bg-indigo-600/80 hover:bg-indigo-600 text-white'
          } disabled:opacity-50`}
        >
          {downloading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
          Download
        </button>
      </div>

      <div className={`mt-2 flex items-center justify-end gap-1 ${isSelf ? 'text-indigo-200/80' : 'text-slate-400'}`}>
        <span className="text-[10px] leading-none">{formattedTime}</span>
        {status}
      </div>
    </div>
  );
};
