import React, { useState } from 'react';
import { Loader2, FileText, Download, ExternalLink, FileArchive, File as FileIcon } from 'lucide-react';
import { mediaApi } from '../../api/mediaApi';
import { resolveGroupMediaKey } from '../../crypto/groupMediaKey';
import { decryptBytesWithGroupKey } from '../../crypto/groupCrypto';
import { decryptMediaBytes } from '../../crypto/mediaCrypto';
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
  // DIRECT encrypted media only (Phase 6D) -- see ImageMessageContent's identical props.
  // directMediaFilename is the AUTHORITATIVE filename (from the decrypted envelope) and must be
  // preferred over `caption` whenever present -- an encrypted DIRECT document's `caption` is never
  // the filename (Phase 6C never puts it there; the envelope's own `filename` field is).
  directMediaKey?: CryptoKey;
  directMediaMimeType?: string;
  directMediaFilename?: string;
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
  directMediaKey,
  directMediaMimeType,
  directMediaFilename,
}) => {
  const [loading, setLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const filename = directMediaFilename || caption || 'Document';
  const isGroupEncrypted = !!(group && currentUserId != null && mediaGroupKeyVersion != null && mediaNonce);
  // Same discriminator as ImageMessageContent: a persisted nonce with no GROUP context means
  // DIRECT encrypted media (Phase 6D) -- see that component's identical comment for why.
  const isDirectEncrypted = !isGroupEncrypted && !!mediaNonce;
  const effectiveMimeType = directMediaMimeType || mimeType || 'application/octet-stream';
  const badgeInfo = getFileBadge(effectiveMimeType, filename);

  const getDecryptedBlob = async (): Promise<Blob> => {
    if (isDirectEncrypted && !directMediaKey) {
      // Envelope decryption failed upstream (App.tsx) -- never fetch/serve the raw ciphertext as
      // if it were the real document.
      throw new Error('MEDIA_KEY_UNAVAILABLE');
    }
    const rawBlob = await mediaApi.getMediaBlob(mediaId!);
    if (!isGroupEncrypted && !isDirectEncrypted) {
      return rawBlob;
    }
    const ciphertext = await rawBlob.arrayBuffer();
    const decryptedBytes = isGroupEncrypted
      ? await (async () => {
          const groupKey = await resolveGroupMediaKey(group!, mediaGroupKeyVersion!, currentUserId!);
          if (!groupKey) {
            throw new Error('GROUP_KEY_UNAVAILABLE');
          }
          return decryptBytesWithGroupKey(groupKey, ciphertext, mediaNonce!);
        })()
      : await decryptMediaBytes(directMediaKey!, ciphertext, mediaNonce!);
    return new Blob([decryptedBytes], { type: effectiveMimeType });
  };

  const handleOpen = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!mediaId || loading || downloading) return;
    if (isDirectEncrypted && !directMediaKey) {
      alert('Failed to open document');
      return;
    }
    setLoading(true);
    try {
      const url = isGroupEncrypted
        ? await mediaApi.getDecryptedGroupMediaObjectUrl(mediaId, effectiveMimeType, async (ciphertext) => {
            const groupKey = await resolveGroupMediaKey(group!, mediaGroupKeyVersion!, currentUserId!);
            if (!groupKey) {
              throw new Error('GROUP_KEY_UNAVAILABLE');
            }
            return decryptBytesWithGroupKey(groupKey, ciphertext, mediaNonce!);
          })
        : isDirectEncrypted
        ? await mediaApi.getDecryptedMediaObjectUrl(mediaId, effectiveMimeType, (ciphertext) =>
            decryptMediaBytes(directMediaKey!, ciphertext, mediaNonce!)
          )
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
