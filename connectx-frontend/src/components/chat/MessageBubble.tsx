import React, { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { createPortal } from 'react-dom';
import {
  Lock,
  AlertCircle,
  Trash2,
  Copy,
  MoreVertical,
  Check,
  CheckCheck,
  Download,
  Loader2,
  CornerUpLeft,
  SmilePlus,
} from 'lucide-react';
import { Message } from '../../types';
import { ImageMessageContent } from './ImageMessageContent';
import { LocationMessageContent } from './LocationMessageContent';
import { DocumentMessageContent } from './DocumentMessageContent';
import { getGoogleMapsLink } from '../../utils/googleMaps';
import { saveImageToGallery } from '../../utils/saveMedia';

const QUICK_REACTIONS = ['❤️', '😂', '👍', '😮', '😢', '🔥'];

const calculateMenuPosition = (
  anchorRect: DOMRect,
  menuWidth: number,
  menuHeight: number,
  isSelf: boolean
): { top: number; left: number } => {
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const PADDING = 10;
  const GAP = 4;

  const spaceBelow = viewportHeight - anchorRect.bottom - PADDING;
  const spaceAbove = anchorRect.top - PADDING;

  let top: number;
  if (spaceBelow >= menuHeight) {
    top = anchorRect.bottom + GAP;
  } else if (spaceAbove >= menuHeight) {
    top = anchorRect.top - menuHeight - GAP;
  } else {
    top = spaceBelow >= spaceAbove ? anchorRect.bottom + GAP : anchorRect.top - menuHeight - GAP;
  }
  top = Math.max(PADDING, Math.min(top, viewportHeight - menuHeight - PADDING));

  let left = isSelf ? anchorRect.right - menuWidth : anchorRect.left;
  left = Math.max(PADDING, Math.min(left, viewportWidth - menuWidth - PADDING));

  return { top, left };
};

const calculateReactionPickerPosition = (
  anchorRect: DOMRect,
  pickerWidth: number,
  pickerHeight: number,
  isSelf: boolean
): { top: number; left: number } => {
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const PADDING = 10;
  const GAP = 6;

  const spaceAbove = anchorRect.top - PADDING;
  const spaceBelow = viewportHeight - anchorRect.bottom - PADDING;

  let top: number;
  if (spaceAbove >= pickerHeight) {
    top = anchorRect.top - pickerHeight - GAP;
  } else if (spaceBelow >= pickerHeight) {
    top = anchorRect.bottom + GAP;
  } else {
    top = spaceAbove >= spaceBelow ? anchorRect.top - pickerHeight - GAP : anchorRect.bottom + GAP;
  }
  top = Math.max(PADDING, Math.min(top, viewportHeight - pickerHeight - PADDING));

  let left = isSelf ? anchorRect.right - pickerWidth : anchorRect.left;
  left = Math.max(PADDING, Math.min(left, viewportWidth - pickerWidth - PADDING));

  return { top, left };
};

interface MessageBubbleProps {
  message: Message;
  isSelf: boolean;
  currentUserId?: number;
  isGroupedWithPrev: boolean;
  isGroupedWithNext: boolean;
  showRawCiphertext: boolean;
  onDeleteMessage: (messageId: number, deleteForEveryone: boolean) => void;
  onReplyMessage?: (message: Message) => void;
  onReactMessage?: (messageId: number, reaction: string) => void;
  onScrollToMessage?: (messageId: number) => void;
}

const MessageBubbleComponent: React.FC<MessageBubbleProps> = ({
  message,
  isSelf,
  currentUserId,
  isGroupedWithPrev,
  isGroupedWithNext,
  showRawCiphertext,
  onDeleteMessage,
  onReplyMessage,
  onReactMessage,
  onScrollToMessage,
}) => {
  const [showMenu, setShowMenu] = useState(false);
  const [showReactionPicker, setShowReactionPicker] = useState(false);
  const [savingImage, setSavingImage] = useState(false);

  const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);
  const [reactionPickerPos, setReactionPickerPos] = useState<{ top: number; left: number } | null>(null);

  const moreButtonRef = useRef<HTMLButtonElement>(null);
  const reactButtonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const reactionPickerRef = useRef<HTMLDivElement>(null);

  const formattedTime = message.sentAt
    ? new Date(message.sentAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : '';

  // Close floating overlays on scroll or window resize/orientation/visualViewport change
  useEffect(() => {
    if (!showMenu && !showReactionPicker) return;

    const handleDismissOnScrollOrResize = () => {
      setShowMenu(false);
      setShowReactionPicker(false);
    };

    window.addEventListener('scroll', handleDismissOnScrollOrResize, true);
    window.addEventListener('resize', handleDismissOnScrollOrResize);
    window.addEventListener('orientationchange', handleDismissOnScrollOrResize);
    window.visualViewport?.addEventListener('resize', handleDismissOnScrollOrResize);
    window.visualViewport?.addEventListener('scroll', handleDismissOnScrollOrResize);

    return () => {
      window.removeEventListener('scroll', handleDismissOnScrollOrResize, true);
      window.removeEventListener('resize', handleDismissOnScrollOrResize);
      window.removeEventListener('orientationchange', handleDismissOnScrollOrResize);
      window.visualViewport?.removeEventListener('resize', handleDismissOnScrollOrResize);
      window.visualViewport?.removeEventListener('scroll', handleDismissOnScrollOrResize);
    };
  }, [showMenu, showReactionPicker]);

  // Dynamically measure and refine menu position based on actual rendered dimensions
  useLayoutEffect(() => {
    if (!showMenu || !moreButtonRef.current || !menuRef.current) return;
    const btnRect = moreButtonRef.current.getBoundingClientRect();
    const menuRect = menuRef.current.getBoundingClientRect();
    const pos = calculateMenuPosition(
      btnRect,
      menuRect.width || 176,
      menuRect.height || (isSelf ? 230 : 190),
      isSelf
    );
    setMenuPos((prev) => {
      if (prev && Math.abs(prev.top - pos.top) < 1 && Math.abs(prev.left - pos.left) < 1) {
        return prev;
      }
      return pos;
    });
  }, [showMenu, isSelf]);

  // Dynamically measure and refine reaction picker position
  useLayoutEffect(() => {
    if (!showReactionPicker || !reactButtonRef.current || !reactionPickerRef.current) return;
    const btnRect = reactButtonRef.current.getBoundingClientRect();
    const pickerRect = reactionPickerRef.current.getBoundingClientRect();
    const pos = calculateReactionPickerPosition(
      btnRect,
      pickerRect.width || 240,
      pickerRect.height || 44,
      isSelf
    );
    setReactionPickerPos((prev) => {
      if (prev && Math.abs(prev.top - pos.top) < 1 && Math.abs(prev.left - pos.left) < 1) {
        return prev;
      }
      return pos;
    });
  }, [showReactionPicker, isSelf]);

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

  const handleReactionClick = (emoji: string) => {
    onReactMessage?.(message.id, emoji);
    setShowReactionPicker(false);
  };

  const handleToggleMenu = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (showMenu) {
      setShowMenu(false);
    } else {
      setShowReactionPicker(false);
      if (moreButtonRef.current) {
        const btnRect = moreButtonRef.current.getBoundingClientRect();
        const initialMenuHeight = isSelf ? 230 : 190;
        const pos = calculateMenuPosition(btnRect, 176, initialMenuHeight, isSelf);
        setMenuPos(pos);
        setShowMenu(true);
      }
    }
  };

  const handleToggleReactionPicker = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (showReactionPicker) {
      setShowReactionPicker(false);
    } else {
      setShowMenu(false);
      if (reactButtonRef.current) {
        const btnRect = reactButtonRef.current.getBoundingClientRect();
        const pos = calculateReactionPickerPosition(btnRect, 240, 44, isSelf);
        setReactionPickerPos(pos);
        setShowReactionPicker(true);
      }
    }
  };

  const isImageMessage = message.messageType === 'IMAGE';
  const isDocMessage = message.messageType === 'DOCUMENT';

  const marginClass = isGroupedWithPrev ? 'mt-0.5 md:mt-1' : 'mt-1.5 md:mt-2.5';

  const radiusClass = isSelf
    ? `${isGroupedWithPrev ? 'rounded-tr-md' : 'rounded-tr-2xl'} ${
        isGroupedWithNext ? 'rounded-br-md' : 'rounded-br-2xl'
      } rounded-l-2xl`
    : `${isGroupedWithPrev ? 'rounded-tl-md' : 'rounded-tl-2xl'} ${
        isGroupedWithNext ? 'rounded-bl-md' : 'rounded-bl-2xl'
      } rounded-r-2xl`;

  const renderStatus = () => {
    if (!isSelf || message.id < 0) return null;
    if (message.readAt) {
      return (
        <CheckCheck
          className="w-3.5 h-3.5 md:w-4 md:h-4 text-sky-400 drop-shadow-[0_0_2px_rgba(56,189,248,0.5)]"
          aria-label="Read"
        />
      );
    }
    if (message.deliveredAt) {
      return (
        <CheckCheck
          className="w-3.5 h-3.5 md:w-4 md:h-4 text-slate-300/80"
          aria-label="Delivered"
        />
      );
    }
    return (
      <Check className="w-3.5 h-3.5 md:w-4 md:h-4 text-slate-300/70" aria-label="Sent" />
    );
  };

  // Group reactions by emoji
  const reactionGroups = (message.reactions || []).reduce<
    Record<string, { count: number; users: string[]; hasUserReacted: boolean }>
  >((acc, r) => {
    if (!acc[r.reaction]) {
      acc[r.reaction] = { count: 0, users: [], hasUserReacted: false };
    }
    acc[r.reaction].count += 1;
    acc[r.reaction].users.push(r.username);
    if (currentUserId && r.userId === currentUserId) {
      acc[r.reaction].hasUserReacted = true;
    }
    return acc;
  }, {});

  const reactionEntries = Object.entries(reactionGroups);

  return (
    <div
      id={`message-${message.id}`}
      className={`flex ${isSelf ? 'justify-end' : 'justify-start'} ${marginClass} group relative`}
    >
      <div
        className={`relative max-w-[85%] md:max-w-[70%] lg:max-w-[65%] transition-all ${
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
        {/* Reply Quote Card if this message is replying to another message */}
        {message.replyToMessageId && (
          <div
            onClick={() => onScrollToMessage?.(message.replyToMessageId!)}
            className="mb-1.5 p-2 rounded-lg bg-black/25 hover:bg-black/35 border-l-4 border-indigo-400 cursor-pointer text-xs transition-colors select-none"
            role="button"
            tabIndex={0}
            title="Click to view quoted message"
          >
            <div className="font-semibold text-indigo-300 dark:text-indigo-200 truncate flex items-center gap-1">
              <CornerUpLeft className="w-3 h-3" />
              <span>{message.replyToSenderUsername || 'User'}</span>
            </div>
            <div className="text-slate-200/90 truncate mt-0.5">
              {message.replyToDeleted ? (
                <span className="italic text-slate-400">This message was deleted</span>
              ) : message.replyToMessageType === 'IMAGE' ? (
                '📷 Photo' + (message.replyToCaption ? ` — ${message.replyToCaption}` : '')
              ) : message.replyToMessageType === 'LOCATION' ? (
                '📍 Location'
              ) : message.replyToMessageType === 'DOCUMENT' ? (
                '📄 ' + (message.replyToCaption || 'Document')
              ) : (
                message.replyToCaption || 'Quoted message'
              )}
            </div>
          </div>
        )}

        {message.deletedForEveryone ? (
          <p className="text-xs italic text-slate-400 flex items-center gap-1.5 select-none">
            <Trash2 className="w-3.5 h-3.5" />
            This message was deleted
          </p>
        ) : showRawCiphertext ? (
          <div className="text-[10px] font-mono break-all text-pink-200/90 space-y-1 select-text">
            <div className="flex items-center gap-1 text-pink-300/80 select-none">
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
          <div className="flex items-start gap-1.5 text-rose-300 text-xs select-none">
            <AlertCircle className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" />
            <span>Unable to decrypt message</span>
          </div>
        ) : (
          <p className="text-[13px] md:text-[15px] md:leading-snug leading-snug whitespace-pre-wrap break-words pr-1 select-text">
            {message.decryptedContent || '🔒 Encrypted message'}
          </p>
        )}

        {!isImageMessage && !isDocMessage && (
          <div
            className={`flex items-center justify-end gap-1 mt-0.5 select-none ${
              isSelf ? 'text-indigo-100/80' : 'text-slate-400'
            }`}
          >
            <span className="text-[10px] md:text-[11px] leading-none">{formattedTime}</span>
            {renderStatus()}
          </div>
        )}

        {/* Reaction Badges */}
        {reactionEntries.length > 0 && (
          <div
            className={`flex flex-wrap gap-1 mt-1 select-none ${
              isSelf ? 'justify-end' : 'justify-start'
            }`}
          >
            {reactionEntries.map(([emoji, data]) => (
              <button
                key={emoji}
                type="button"
                onClick={() => handleReactionClick(emoji)}
                className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-xs transition-all shadow-sm ${
                  data.hasUserReacted
                    ? 'bg-indigo-500/30 border border-indigo-400/60 text-white font-semibold'
                    : 'bg-slate-900/80 border border-slate-700/60 text-slate-200 hover:bg-slate-800'
                }`}
                title={`Reacted by: ${data.users.join(', ')}`}
              >
                <span>{emoji}</span>
                {data.count > 1 && (
                  <span className="text-[10px] opacity-90">{data.count}</span>
                )}
              </button>
            ))}
          </div>
        )}

        {/* Floating Quick Action Buttons on Hover (Reply, React, More) */}
        {!message.deletedForEveryone && message.id > 0 && (
          <div
            className={`absolute top-0 -translate-y-1/2 ${
              isSelf ? 'left-0 -translate-x-full pr-1.5' : 'right-0 translate-x-full pl-1.5'
            } hidden group-hover:flex items-center gap-0.5 bg-slate-900/90 backdrop-blur-sm border border-slate-700/70 rounded-full py-0.5 px-1 shadow-lg z-20 transition-all select-none`}
          >
            {/* Quick React Trigger */}
            <button
              ref={reactButtonRef}
              type="button"
              onClick={handleToggleReactionPicker}
              className="p-1 text-slate-400 hover:text-amber-400 hover:bg-slate-800 rounded-full transition-colors"
              aria-label="Add reaction"
              title="Add reaction"
            >
              <SmilePlus className="w-3.5 h-3.5" />
            </button>

            {/* Quick Reply Trigger */}
            {onReplyMessage && (
              <button
                type="button"
                onClick={() => onReplyMessage(message)}
                className="p-1 text-slate-400 hover:text-indigo-400 hover:bg-slate-800 rounded-full transition-colors"
                aria-label="Reply to message"
                title="Reply"
              >
                <CornerUpLeft className="w-3.5 h-3.5" />
              </button>
            )}

            {/* More Menu Trigger */}
            <button
              ref={moreButtonRef}
              type="button"
              onClick={handleToggleMenu}
              className="p-1 text-slate-400 hover:text-white hover:bg-slate-800 rounded-full transition-colors"
              aria-label="More options"
              title="More"
            >
              <MoreVertical className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* Floating Quick Reaction Picker via Portal */}
        {showReactionPicker &&
          reactionPickerPos &&
          createPortal(
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setShowReactionPicker(false)}
                onTouchStart={() => setShowReactionPicker(false)}
              />
              <div
                ref={reactionPickerRef}
                style={{
                  position: 'fixed',
                  top: `${reactionPickerPos.top}px`,
                  left: `${reactionPickerPos.left}px`,
                }}
                className="z-50 flex items-center gap-1 bg-slate-900 border border-slate-700 rounded-full py-1 px-2 shadow-2xl animate-pop-in select-none"
              >
                {QUICK_REACTIONS.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => handleReactionClick(emoji)}
                    className="hover:scale-125 active:scale-95 transition-transform text-lg px-1.5 py-0.5 rounded-full hover:bg-slate-800"
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            </>,
            document.body
          )}

        {/* Message Options Dropdown Menu via Portal */}
        {showMenu &&
          menuPos &&
          createPortal(
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setShowMenu(false)}
                onTouchStart={() => setShowMenu(false)}
              />
              <div
                ref={menuRef}
                style={{
                  position: 'fixed',
                  top: `${menuPos.top}px`,
                  left: `${menuPos.left}px`,
                }}
                className="z-50 w-44 bg-slate-900 border border-slate-700 rounded-xl shadow-2xl p-1 text-xs animate-pop-in select-none"
              >
                {onReplyMessage && (
                  <button
                    type="button"
                    onClick={() => {
                      onReplyMessage(message);
                      setShowMenu(false);
                    }}
                    className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                  >
                    <CornerUpLeft className="w-3.5 h-3.5" /> Reply
                  </button>
                )}
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
                {message.messageType === 'LOCATION' &&
                  message.latitude != null &&
                  message.longitude != null && (
                    <button
                      type="button"
                      onClick={() => {
                        copyToClipboard(
                          getGoogleMapsLink(message.latitude!, message.longitude!)
                        );
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
            </>,
            document.body
          )}
      </div>
    </div>
  );
};

export const MessageBubble = React.memo(MessageBubbleComponent);
