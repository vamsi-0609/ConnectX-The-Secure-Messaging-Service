import React, { useState } from 'react';
import { Lock, AlertCircle, Trash2, Copy, MoreVertical, Check, CheckCheck, Download, Loader2 } from 'lucide-react';
import { Message } from '../../types';
import { ImageMessageContent } from './ImageMessageContent';
import { LocationMessageContent } from './LocationMessageContent';
import { DocumentMessageContent } from './DocumentMessageContent';
import { getGoogleMapsLink } from '../../utils/googleMaps';
import { saveImageToGallery } from '../../utils/saveMedia';

interface MessageBubbleProps {
  message: Message;
  isSelf: boolean;
  isGroupedWithPrev: boolean;
  isGroupedWithNext: boolean;
  showRawCiphertext: boolean;
  onDeleteMessage: (messageId: number, deleteForEveryone: boolean) => void;
}

export const MessageBubble: React.FC<MessageBubbleProps> = ({
  message,
  isSelf,
  isGroupedWithPrev,
  isGroupedWithNext,
  showRawCiphertext,
  onDeleteMessage,
}) => {
  const [showMenu, setShowMenu] = useState(false);
  const [savingImage, setSavingImage] = useState(false);

  const formattedTime = message.sentAt
    ? new Date(message.sentAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : '';

  const copyToClipboard = (text: string) => {
    navigator.clipboard?.writeText(text);
  };

  const handleSaveToGallery = async () => {
    if (message.messageType !== 'IMAGE' || savingImage) return;

    setSavingImage(true);
    try {
      await saveImageToGallery({
        mediaId: message.mediaId,
        localMediaUrl: message.localMediaUrl,
        mimeType: message.mimeType,
      });
      setShowMenu(false);
    } catch (err: unknown) {
      const messageText = err instanceof Error ? err.message : 'Failed to save image';
      alert(messageText);
    } finally {
      setSavingImage(false);
    }
  };

  const isImageMessage = message.messageType === 'IMAGE';
  const isDocMessage = message.messageType === 'DOCUMENT';

  const marginClass = isGroupedWithPrev ? 'mt-0.5 md:mt-1' : 'mt-1.5 md:mt-2.5';

  const radiusClass = isSelf
    ? `${isGroupedWithPrev ? 'rounded-tr-md' : 'rounded-tr-2xl'} ${isGroupedWithNext ? 'rounded-br-md' : 'rounded-br-2xl'} rounded-l-2xl`
    : `${isGroupedWithPrev ? 'rounded-tl-md' : 'rounded-tl-2xl'} ${isGroupedWithNext ? 'rounded-bl-md' : 'rounded-bl-2xl'} rounded-r-2xl`;

  const renderStatus = () => {
    if (!isSelf || message.id < 0) return null;
    if (message.readAt) {
      return <CheckCheck className="w-3.5 h-3.5 md:w-4 md:h-4 text-sky-300" aria-label="Read" />;
    }
    if (message.deliveredAt) {
      return <CheckCheck className="w-3.5 h-3.5 md:w-4 md:h-4 text-indigo-200/80" aria-label="Delivered" />;
    }
    return <Check className="w-3.5 h-3.5 md:w-4 md:h-4 text-indigo-200/70" aria-label="Sent" />;
  };

  return (
    <div className={`flex ${isSelf ? 'justify-end' : 'justify-start'} ${marginClass} group`}>
      <div
        className={`relative max-w-[82%] md:max-w-[68%] lg:max-w-[65%] ${
          isImageMessage || isDocMessage
            ? 'border-0 bg-transparent p-0 shadow-none'
            : `${radiusClass} shadow-sm ${
                message.messageType === 'LOCATION'
                  ? 'px-1.5 py-1.5 md:px-2 md:py-2'
                  : 'px-3 py-1.5 md:px-4 md:py-2'
              } ${
                isSelf
                  ? 'bg-gradient-to-br from-indigo-600 to-violet-600 text-white'
                  : 'bg-slate-800/95 text-slate-100 border border-slate-700/50'
              }`
        }`}
      >
        {message.deletedForEveryone ? (
          <p className="text-xs italic text-slate-400 flex items-center gap-1.5">
            <Trash2 className="w-3 h-3" />
            This message was deleted
          </p>
        ) : showRawCiphertext ? (
          <div className="text-[10px] font-mono break-all text-pink-200/90 space-y-1">
            <div className="flex items-center gap-1 text-pink-300/80">
              <Lock className="w-3 h-3" />
              <span>Ciphertext</span>
            </div>
            <div>{message.ciphertext}</div>
          </div>
        ) : message.messageType === 'IMAGE' ? (
          <ImageMessageContent
            mediaId={message.mediaId}
            mimeType={message.mimeType}
            localMediaUrl={message.localMediaUrl}
            caption={message.caption}
            isSelf={isSelf}
            formattedTime={formattedTime}
            status={renderStatus()}
          />
        ) : message.messageType === 'LOCATION' ? (
          <LocationMessageContent
            latitude={message.latitude}
            longitude={message.longitude}
            locationLabel={message.locationLabel}
            isSelf={isSelf}
          />
        ) : message.messageType === 'DOCUMENT' ? (
          <DocumentMessageContent
            mediaId={message.mediaId}
            mimeType={message.mimeType}
            fileSizeBytes={message.fileSizeBytes}
            caption={message.caption}
            isSelf={isSelf}
            formattedTime={formattedTime}
            status={renderStatus()}
          />
        ) : message.decryptionError ? (
          <div className="flex items-start gap-1.5 text-rose-300 text-xs">
            <AlertCircle className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" />
            <span>Unable to decrypt message</span>
          </div>
        ) : (
          <p className="text-[13px] md:text-[15px] md:leading-snug leading-snug whitespace-pre-wrap break-words pr-1">
            {message.decryptedContent || '🔒 Encrypted message'}
          </p>
        )}

        {!isImageMessage && !isDocMessage && (
          <div className={`flex items-center justify-end gap-1 mt-0.5 ${isSelf ? 'text-indigo-100/80' : 'text-slate-400'}`}>
            <span className="text-[10px] md:text-[11px] leading-none">{formattedTime}</span>
            {renderStatus()}
          </div>
        )}

        {!message.deletedForEveryone && message.id > 0 && (
          <button
            type="button"
            onClick={() => setShowMenu(!showMenu)}
            className={`absolute ${isSelf ? '-left-8' : '-right-8'} top-1/2 -translate-y-1/2 p-1 rounded-full opacity-0 group-hover:opacity-100 focus:opacity-100 text-slate-400 hover:text-white hover:bg-slate-800/80 transition-opacity`}
            aria-label="Message options"
          >
            <MoreVertical className="w-3.5 h-3.5" />
          </button>
        )}

        {showMenu && (
          <>
            <div className="fixed inset-0 z-20" onClick={() => setShowMenu(false)} />
            <div
              className={`absolute z-30 top-full mt-1 ${isSelf ? 'right-0' : 'left-0'} w-44 bg-slate-900 border border-slate-700 rounded-xl shadow-xl p-1 text-xs`}
            >
              {message.messageType === 'IMAGE' && (
                <button
                  type="button"
                  onClick={handleSaveToGallery}
                  disabled={savingImage}
                  className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200 disabled:opacity-60"
                >
                  {savingImage ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Download className="w-3.5 h-3.5" />
                  )}
                  Save to gallery
                </button>
              )}
              {message.messageType !== 'IMAGE' && message.messageType !== 'LOCATION' && (
                <button
                  type="button"
                  onClick={() => {
                    copyToClipboard(message.decryptedContent || message.ciphertext);
                    setShowMenu(false);
                  }}
                  className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                >
                  <Copy className="w-3.5 h-3.5" /> Copy
                </button>
              )}
              {message.messageType === 'LOCATION' && message.latitude != null && message.longitude != null && (
                <button
                  type="button"
                  onClick={() => {
                    copyToClipboard(getGoogleMapsLink(message.latitude!, message.longitude!));
                    setShowMenu(false);
                  }}
                  className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                >
                  <Copy className="w-3.5 h-3.5" /> Copy map link
                </button>
              )}
              {message.messageType === 'IMAGE' && message.caption && (
                <button
                  type="button"
                  onClick={() => {
                    copyToClipboard(message.caption || '');
                    setShowMenu(false);
                  }}
                  className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                >
                  <Copy className="w-3.5 h-3.5" /> Copy caption
                </button>
              )}
              <button
                type="button"
                onClick={() => {
                  onDeleteMessage(message.id, false);
                  setShowMenu(false);
                }}
                className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-amber-300"
              >
                <Trash2 className="w-3.5 h-3.5" /> Delete for me
              </button>
              {isSelf && (
                <button
                  type="button"
                  onClick={() => {
                    onDeleteMessage(message.id, true);
                    setShowMenu(false);
                  }}
                  className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-rose-300"
                >
                  <Trash2 className="w-3.5 h-3.5" /> Delete for all
                </button>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};
