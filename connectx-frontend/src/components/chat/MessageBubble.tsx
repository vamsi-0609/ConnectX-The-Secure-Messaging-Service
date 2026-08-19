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
  Pencil,
  Pin,
  PinOff,
  Star,
  Forward,
  CheckCircle2,
  Circle,
} from 'lucide-react';
import { Message, User } from '../../types';
import { ImageMessageContent } from './ImageMessageContent';
import { LocationMessageContent } from './LocationMessageContent';
import { DocumentMessageContent } from './DocumentMessageContent';
import { getGoogleMapsLink } from '../../utils/googleMaps';
import { saveImageToGallery } from '../../utils/saveMedia';
import { linkifyText } from '../../utils/linkify';
import { UserAvatar } from '../common/UserAvatar';

const QUICK_REACTIONS = ['❤️', '😂', '👍', '😮', '😢', '🔥'];
const EDIT_WINDOW_MS = 15 * 60 * 1000;
const LONG_PRESS_MS = 450;

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
  // GROUP conversations only. isGroup gates rendering the sender name/avatar at all; senderUser
  // is the resolved member (from MessageFeed's senderById lookup, itself built from the already-
  // loaded group member list -- never fetched per-message). Undefined when the sender isn't a
  // currently-cached member (e.g. history from someone who's since left); the name then falls
  // back to the message's own senderUsername, which the backend always populates.
  isGroup?: boolean;
  senderUser?: User;
  isGroupedWithPrev: boolean;
  isGroupedWithNext: boolean;
  showRawCiphertext: boolean;
  onDeleteMessage: (messageId: number, deleteForEveryone: boolean) => void;
  onReplyMessage?: (message: Message) => void;
  onReactMessage?: (messageId: number, reaction: string) => void;
  onScrollToMessage?: (messageId: number) => void;
  /** The locally-known copy of the message being replied to, if still loaded, used to show its real content in the quote card. */
  quotedMessage?: Message;
  onEditMessage?: (message: Message) => void;
  onPinMessage?: (messageId: number) => void;
  onUnpinMessage?: (messageId: number) => void;
  onStarMessage?: (messageId: number) => void;
  onUnstarMessage?: (messageId: number) => void;
  onForwardMessage?: (message: Message) => void;
  selectionMode?: boolean;
  isSelected?: boolean;
  onToggleSelect?: (message: Message) => void;
  onEnterSelectionMode?: (message: Message) => void;
}

const MessageBubbleComponent: React.FC<MessageBubbleProps> = ({
  message,
  isSelf,
  currentUserId,
  isGroup = false,
  senderUser,
  isGroupedWithPrev,
  isGroupedWithNext,
  showRawCiphertext,
  onDeleteMessage,
  onReplyMessage,
  onReactMessage,
  onScrollToMessage,
  quotedMessage,
  onEditMessage,
  onPinMessage,
  onUnpinMessage,
  onStarMessage,
  onUnstarMessage,
  onForwardMessage,
  selectionMode = false,
  isSelected = false,
  onToggleSelect,
  onEnterSelectionMode,
}) => {
  const [showMenu, setShowMenu] = useState(false);
  const [showReactionPicker, setShowReactionPicker] = useState(false);
  const [savingImage, setSavingImage] = useState(false);
  const [justSavedImage, setJustSavedImage] = useState(false);

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
      menuRect.height || (isSelf ? 380 : 300),
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
    if (message.messageType !== 'IMAGE' || savingImage || justSavedImage) return;

    setSavingImage(true);
    try {
      await saveImageToGallery({
        mediaId: message.mediaId,
        localMediaUrl: message.localMediaUrl,
        mimeType: message.mimeType,
      });
      setJustSavedImage(true);
      window.setTimeout(() => {
        setJustSavedImage(false);
        setShowMenu(false);
      }, 900);
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

  const longPressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const longPressFiredRef = useRef(false);

  const clearLongPressTimer = () => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }
  };

  useEffect(() => clearLongPressTimer, []);

  const handleBubblePointerDown = (e: React.PointerEvent) => {
    if (selectionMode || message.deletedForEveryone || message.id < 0 || e.pointerType === 'mouse') return;
    longPressFiredRef.current = false;
    clearLongPressTimer();
    longPressTimerRef.current = setTimeout(() => {
      longPressFiredRef.current = true;
      onEnterSelectionMode?.(message);
    }, LONG_PRESS_MS);
  };

  const handleBubblePointerUpOrLeave = () => {
    clearLongPressTimer();
  };

  const handleBubbleClick = (e: React.MouseEvent) => {
    if (longPressFiredRef.current) {
      // Swallow the trailing click the browser fires after the long-press's
      // pointerup — onEnterSelectionMode already selects this message.
      longPressFiredRef.current = false;
      return;
    }
    if (!selectionMode) return;
    e.preventDefault();
    e.stopPropagation();
    onToggleSelect?.(message);
  };

  const isEditable =
    isSelf &&
    !selectionMode &&
    message.messageType === 'TEXT' &&
    !message.deletedForEveryone &&
    message.id > 0 &&
    Date.now() - new Date(message.sentAt).getTime() < EDIT_WINDOW_MS;

  const isPinned = !!message.pinnedAt;

  const handleToggleMenu = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (showMenu) {
      setShowMenu(false);
    } else {
      setShowReactionPicker(false);
      if (moreButtonRef.current) {
        const btnRect = moreButtonRef.current.getBoundingClientRect();
        const initialMenuHeight = isSelf ? 380 : 300;
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

  // WhatsApp/Telegram-style group identity: the name sits once above the first bubble of a
  // consecutive run from the same sender, and the small avatar sits once at the bottom of that
  // same run (aligned with its last bubble) -- never repeated on every message in between. Never
  // shown for the viewer's own messages ("Do NOT show my own name repeatedly").
  const showGroupSenderInfo = isGroup && !isSelf;
  const showGroupSenderName = showGroupSenderInfo && !isGroupedWithPrev;
  const showGroupSenderAvatar = showGroupSenderInfo && !isGroupedWithNext;
  const groupSenderDisplayName = senderUser?.displayName || senderUser?.username || message.senderUsername || 'Member';

  return (
    <div
      id={`message-${message.id}`}
      className={`flex items-end gap-2 ${isSelf ? 'justify-end' : 'justify-start'} ${marginClass} group relative`}
    >
      {selectionMode && message.id > 0 && (
        <button
          type="button"
          onClick={() => onToggleSelect?.(message)}
          className="flex-shrink-0 p-0.5 rounded-full transition-transform active:scale-90 self-center"
          aria-label={isSelected ? 'Deselect message' : 'Select message'}
        >
          {isSelected ? (
            <CheckCircle2 className="w-5 h-5 text-indigo-500" />
          ) : (
            <Circle className="w-5 h-5 text-slate-400" />
          )}
        </button>
      )}
      {showGroupSenderInfo && (
        <div className="w-6 h-6 flex-shrink-0 self-end">
          {showGroupSenderAvatar &&
            (senderUser ? (
              <UserAvatar user={senderUser} size="xs" viewable={false} passive className="!w-6 !h-6 text-[10px]" />
            ) : (
              <div
                className="w-6 h-6 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-[10px] font-semibold text-white select-none"
                aria-hidden
              >
                {groupSenderDisplayName.charAt(0).toUpperCase()}
              </div>
            ))}
        </div>
      )}
      <div className={`flex flex-col max-w-[85%] md:max-w-[70%] lg:max-w-[65%] ${isSelf ? 'items-end' : 'items-start'}`}>
        {showGroupSenderName && (
          <span className="text-[11px] md:text-xs font-semibold text-violet-400 dark:text-violet-300 mb-0.5 px-1 truncate max-w-full select-none">
            {groupSenderDisplayName}
          </span>
        )}
        <div
        onPointerDown={handleBubblePointerDown}
        onPointerUp={handleBubblePointerUpOrLeave}
        onPointerLeave={handleBubblePointerUpOrLeave}
        onPointerCancel={handleBubblePointerUpOrLeave}
        onClick={handleBubbleClick}
        className={`relative transition-all ${selectionMode ? 'cursor-pointer' : ''} ${
          isSelected ? 'ring-2 ring-indigo-500 rounded-2xl' : isPinned ? 'ring-1 ring-amber-400/50 rounded-2xl' : ''
        } ${
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
        {message.forwarded && !isImageMessage && !isDocMessage && (
          <div className={`flex items-center gap-1 text-[11px] italic mb-1 select-none ${isSelf ? 'text-indigo-100/70' : 'text-slate-400'}`}>
            <Forward className="w-3 h-3" />
            <span>Forwarded</span>
          </div>
        )}
        {/* Reply Quote Card if this message is replying to another message */}
        {message.replyToMessageId && (() => {
          // Prefer the real content of the locally-loaded original message (like
          // WhatsApp does) over the backend's replyToCaption, which is only ever
          // populated for media captions -- for TEXT replies it's always empty
          // since the server never sees plaintext in an E2E-encrypted chat.
          const quotedType = quotedMessage?.messageType || message.replyToMessageType;
          const quotedCaption = quotedMessage?.caption ?? message.replyToCaption;
          const quotedText = quotedMessage?.decryptedContent || message.replyToCaption;

          return (
            <div
              onClick={() => onScrollToMessage?.(message.replyToMessageId!)}
              className="mb-1.5 p-2 rounded-lg bg-black/25 hover:bg-black/35 active:scale-[0.98] border-l-4 border-indigo-400 cursor-pointer text-xs transition-all select-none"
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
                ) : quotedType === 'IMAGE' ? (
                  '📷 Photo' + (quotedCaption ? ` — ${quotedCaption}` : '')
                ) : quotedType === 'LOCATION' ? (
                  '📍 Location'
                ) : quotedType === 'DOCUMENT' ? (
                  '📄 ' + (quotedCaption || 'Document')
                ) : (
                  quotedText || 'Message'
                )}
              </div>
            </div>
          );
        })()}

        {message.deletedForEveryone ? (
          <p className="text-xs italic text-slate-400 flex items-center gap-1.5 select-none">
            <Trash2 className="w-3.5 h-3.5" />
            This message was deleted
          </p>
        ) : showRawCiphertext ? (
          <div className="text-[10px] font-mono break-all text-pink-200/90 space-y-1 select-none">
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
          <p className="text-[13px] md:text-[15px] md:leading-snug leading-snug whitespace-pre-wrap break-words pr-1 select-none">
            {message.decryptedContent ? linkifyText(message.decryptedContent) : '🔒 Encrypted message'}
          </p>
        )}

        {!isImageMessage && !isDocMessage && (
          <div
            className={`flex items-center justify-end gap-1 mt-0.5 select-none ${
              isSelf ? 'text-indigo-100/80' : 'text-slate-400'
            }`}
          >
            {isPinned && <Pin className="w-3 h-3" aria-label="Pinned" />}
            {message.starred && <Star className="w-3 h-3 fill-current" aria-label="Starred" />}
            {message.editedAt && <span className="text-[10px] md:text-[11px] italic opacity-80">edited</span>}
            <span className="text-[10px] md:text-[11px] leading-none">{formattedTime}</span>
            {renderStatus()}
          </div>
        )}

        {/* Reaction Badges */}
        {reactionEntries.length > 0 && (
          <div
            className={`flex flex-wrap gap-1 mt-1.5 select-none ${
              isSelf ? 'justify-end' : 'justify-start'
            }`}
          >
            {reactionEntries.map(([emoji, data]) => (
              <button
                key={emoji}
                type="button"
                onClick={() => handleReactionClick(emoji)}
                className={`animate-pop-in inline-flex items-center gap-1 min-h-[22px] px-1.5 py-0.5 rounded-full text-xs leading-none shadow-sm transition-all hover:scale-110 active:scale-95 ${
                  data.hasUserReacted
                    ? 'bg-indigo-500/30 border border-indigo-400/60 text-white font-semibold'
                    : 'bg-slate-900/80 border border-slate-700/60 text-slate-200 hover:bg-slate-800'
                }`}
                title={`Reacted by: ${data.users.join(', ')}`}
              >
                <span className="text-sm leading-none">{emoji}</span>
                {data.count > 1 && (
                  <span className="text-[10px] font-medium opacity-90 leading-none">{data.count}</span>
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
            } hidden [@media(hover:hover)]:group-hover:flex items-center gap-0.5 bg-slate-900/90 backdrop-blur-sm border border-slate-700/70 rounded-full py-1 px-1 shadow-lg z-20 transition-all select-none animate-pop-in`}
          >
            {/* Quick React Trigger */}
            <button
              ref={reactButtonRef}
              type="button"
              onClick={handleToggleReactionPicker}
              className="p-1.5 text-slate-400 hover:text-amber-400 hover:bg-slate-800 active:scale-90 rounded-full transition-all"
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
                className="p-1.5 text-slate-400 hover:text-indigo-400 hover:bg-slate-800 active:scale-90 rounded-full transition-all"
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
              className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-800 active:scale-90 rounded-full transition-all"
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
                className="z-50 flex items-center gap-0.5 bg-slate-900 border border-slate-700 rounded-full py-1.5 px-2 shadow-2xl animate-pop-in select-none"
              >
                {QUICK_REACTIONS.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => handleReactionClick(emoji)}
                    className="hover:scale-125 active:scale-90 transition-transform duration-150 text-xl leading-none w-9 h-9 flex items-center justify-center rounded-full hover:bg-slate-800"
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
                {isEditable && onEditMessage && (
                  <button
                    type="button"
                    onClick={() => {
                      onEditMessage(message);
                      setShowMenu(false);
                    }}
                    className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                  >
                    <Pencil className="w-3.5 h-3.5" /> Edit
                  </button>
                )}
                {onForwardMessage && (
                  <button
                    type="button"
                    onClick={() => {
                      onForwardMessage(message);
                      setShowMenu(false);
                    }}
                    className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                  >
                    <Forward className="w-3.5 h-3.5" /> Forward
                  </button>
                )}
                {onEnterSelectionMode && (
                  <button
                    type="button"
                    onClick={() => {
                      onEnterSelectionMode(message);
                      setShowMenu(false);
                    }}
                    className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                  >
                    <CheckCircle2 className="w-3.5 h-3.5" /> Select
                  </button>
                )}
                {isPinned
                  ? onUnpinMessage && (
                      <button
                        type="button"
                        onClick={() => {
                          onUnpinMessage(message.id);
                          setShowMenu(false);
                        }}
                        className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                      >
                        <PinOff className="w-3.5 h-3.5" /> Unpin
                      </button>
                    )
                  : onPinMessage && (
                      <button
                        type="button"
                        onClick={() => {
                          onPinMessage(message.id);
                          setShowMenu(false);
                        }}
                        className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                      >
                        <Pin className="w-3.5 h-3.5" /> Pin
                      </button>
                    )}
                {message.starred
                  ? onUnstarMessage && (
                      <button
                        type="button"
                        onClick={() => {
                          onUnstarMessage(message.id);
                          setShowMenu(false);
                        }}
                        className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                      >
                        <Star className="w-3.5 h-3.5 fill-current text-amber-400" /> Unstar
                      </button>
                    )
                  : onStarMessage && (
                      <button
                        type="button"
                        onClick={() => {
                          onStarMessage(message.id);
                          setShowMenu(false);
                        }}
                        className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200"
                      >
                        <Star className="w-3.5 h-3.5" /> Star
                      </button>
                    )}
                {message.messageType === 'IMAGE' && (
                  <button
                    type="button"
                    onClick={handleSaveToGallery}
                    disabled={savingImage || justSavedImage}
                    className="w-full text-left px-3 py-2 hover:bg-slate-800 rounded-lg flex items-center gap-2 text-slate-200 disabled:opacity-90 transition-colors"
                  >
                    {savingImage ? (
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    ) : justSavedImage ? (
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                    ) : (
                      <Download className="w-3.5 h-3.5" />
                    )}
                    <span className={justSavedImage ? 'text-emerald-400 font-medium' : undefined}>
                      {justSavedImage ? 'Saved' : 'Save to gallery'}
                    </span>
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
    </div>
  );
};

export const MessageBubble = React.memo(MessageBubbleComponent);
