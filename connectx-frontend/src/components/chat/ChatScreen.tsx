import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { X, Copy, Trash2, Forward, Star, SmilePlus, Pin, PinOff, CornerUpLeft, Pencil } from 'lucide-react';
import { User, Message, ReplyTarget, Conversation, ConnectionRequestDto, RelationshipStatus, Group, ConversationMember } from '../../types';
import { ChatHeader } from './ChatHeader';
import { MessageFeed } from './MessageFeed';
import { MessageInput } from './MessageInput';
import { ChatRelationshipGate } from './ChatRelationshipGate';
import { ChatWallpaperBackground } from './ChatWallpaperBackground';
import { ForwardMessageModal } from './ForwardMessageModal';
import { GroupChatHeader } from '../group/GroupChatHeader';
import { GroupComposerPlaceholder } from '../group/GroupComposerPlaceholder';
import {
  ChatWallpaperSetting,
  getConversationWallpaper,
  getWallpaperImageUrl,
  setConversationWallpaper,
} from '../../utils/chatWallpaper';

type ForwardResult = { conversationId: number; success: boolean; error?: string };

const QUICK_REACTIONS = ['❤️', '😂', '👍', '😮', '😢', '🔥'];

// Mirrors GroupComposerPlaceholder's own restriction check -- UI-only, never authorization
// (MessageService/GroupAuthorizationService re-enforce who_can_send_messages server-side
// regardless). Decides only whether to render the real composer or the placeholder here.
const canSendInGroup = (group: Group): boolean =>
  !(group.whoCanSendMessages === 'ADMINS_ONLY' && group.currentUserRole === 'MEMBER');

interface ChatScreenProps {
  recipient: User | null;
  // GROUP conversations render GroupChatHeader/GroupComposerPlaceholder instead of the DIRECT
  // ChatHeader/MessageInput/ChatRelationshipGate trio -- `recipient`/`relationship`/`sentRequest`/
  // `receivedRequest` below are simply unused (and meaningless) for a group. `group` is best-effort
  // (App.tsx's groupInfoById cache) and may briefly be undefined right after opening a group.
  isGroup?: boolean;
  group?: Group | null;
  // Active member list for the open GROUP conversation (App.tsx's groupMembersById cache) --
  // used only to resolve a message's sender display name/avatar in MessageFeed/MessageBubble.
  // Undefined until the cache has fetched it at least once; MessageBubble falls back to the
  // message's own senderUsername when a sender id isn't found here.
  groupMembers?: ConversationMember[];
  conversationId: number;
  messages: Message[];
  currentUserId: number;
  showRawCiphertext: boolean;
  showInfoDrawer: boolean;
  isDarkMode: boolean;
  isMuted?: boolean;
  isTyping?: boolean;
  hasMore?: boolean;
  isLoadingOlder?: boolean;
  onLoadOlderMessages?: () => void;
  onToggleCiphertext: () => void;
  onToggleInfoDrawer: () => void;
  onBack: () => void;
  onDeleteMessage: (messageId: number, deleteForEveryone: boolean) => void;
  onOptimisticMessage: (
    plaintext: string,
    ciphertext: string,
    nonce: string,
    recipientDeviceId: number,
    replyToMessageId?: number,
    clientTempId?: string
  ) => void;
  // Rolls back the optimistic bubble onOptimisticMessage just inserted when the actual send
  // request fails (e.g. backend NOT_CONNECTED) -- so a rejected send never lingers as a
  // misleading "sent" message. Text-only; see App.tsx's handler for why.
  onOptimisticMessageFailed?: (clientTempId: string) => void;
  onOptimisticImageMessage: (
    mediaId: number,
    caption: string | undefined,
    localPreviewUrl: string,
    mimeType: string,
    replyToMessageId?: number
  ) => void;
  onOptimisticLocationMessage: (
    latitude: number,
    longitude: number,
    locationLabel: string | undefined,
    replyToMessageId?: number
  ) => void;
  onOptimisticDocumentMessage: (
    mediaId: number,
    filename: string,
    mimeType: string,
    fileSizeBytes: number,
    replyToMessageId?: number
  ) => void;
  onMessageSent?: () => void;
  onClearChat?: () => Promise<void>;
  onMuteChat?: (duration: '8_HOURS' | '1_WEEK' | 'ALWAYS') => Promise<void>;
  onUnmuteChat?: () => Promise<void>;
  onExportChat?: () => Promise<void>;
  exportingChat?: boolean;
  onReactMessage?: (messageId: number, reaction: string) => Promise<void>;
  onEditMessage?: (messageId: number, newPlaintext: string) => Promise<void>;
  pinnedMessage?: Message | null;
  onPinMessage?: (messageId: number) => Promise<void>;
  onUnpinMessage?: (messageId: number) => Promise<void>;
  onStarMessage?: (messageId: number) => Promise<void>;
  onUnstarMessage?: (messageId: number) => Promise<void>;
  onForwardMessages?: (messages: Message[], targetConversationIds: number[]) => Promise<ForwardResult[]>;
  conversations: Conversation[];
  onScrollToPinned?: (messageId: number) => void;
  initialSharedMedia?: { images: File[]; docs: File[] } | null;
  onSharedMediaConsumed?: () => void;
  // Current relationship with `recipient` (from utils/relationship.ts's getRelationshipStatus(),
  // the same derivation UserSearchModal/ContactInfoDrawer use). Only 'CONNECTED' renders the real
  // composer; every other value renders ChatRelationshipGate instead -- a past conversation must
  // never grant a currently-valid send permission. Undefined (e.g. recipient unresolved) also
  // gates, failing safe rather than open.
  relationship?: RelationshipStatus;
  sentRequest?: ConnectionRequestDto;
  receivedRequest?: ConnectionRequestDto;
  onSendConnectionRequest?: (userId: number) => Promise<void>;
  onCancelConnectionRequest?: (requestId: number, userId: number) => Promise<void>;
  onAcceptConnectionRequest?: (requestId: number, userId: number) => Promise<void>;
  onRejectConnectionRequest?: (requestId: number, userId: number) => Promise<void>;
  onUnblockUser?: (userId: number) => Promise<void>;
}

export const ChatScreen: React.FC<ChatScreenProps> = ({
  recipient,
  isGroup = false,
  group,
  groupMembers,
  conversationId,
  messages,
  currentUserId,
  showRawCiphertext,
  showInfoDrawer,
  isDarkMode,
  isMuted,
  isTyping,
  hasMore,
  isLoadingOlder,
  onLoadOlderMessages,
  onToggleCiphertext,
  onToggleInfoDrawer,
  onBack,
  onDeleteMessage,
  onOptimisticMessage,
  onOptimisticMessageFailed,
  onOptimisticImageMessage,
  onOptimisticLocationMessage,
  onOptimisticDocumentMessage,
  onMessageSent,
  onClearChat,
  onMuteChat,
  onUnmuteChat,
  onExportChat,
  exportingChat,
  onReactMessage,
  onEditMessage,
  pinnedMessage,
  onPinMessage,
  onUnpinMessage,
  onStarMessage,
  onUnstarMessage,
  onForwardMessages,
  conversations,
  initialSharedMedia,
  onSharedMediaConsumed,
  relationship,
  sentRequest,
  receivedRequest,
  onSendConnectionRequest,
  onCancelConnectionRequest,
  onAcceptConnectionRequest,
  onRejectConnectionRequest,
  onUnblockUser,
}) => {
  const [wallpaper, setWallpaper] = useState<ChatWallpaperSetting>(() =>
    getConversationWallpaper(conversationId)
  );
  const [replyTarget, setReplyTarget] = useState<ReplyTarget | null>(null);
  const [editTarget, setEditTarget] = useState<Message | null>(null);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedMessageIds, setSelectedMessageIds] = useState<Set<number>>(new Set());
  const [forwardQueue, setForwardQueue] = useState<Message[] | null>(null);
  const [showBulkReactPicker, setShowBulkReactPicker] = useState(false);

  useEffect(() => {
    setWallpaper(getConversationWallpaper(conversationId));
    setReplyTarget(null);
    setEditTarget(null);
    setSelectionMode(false);
    setSelectedMessageIds(new Set());
    setForwardQueue(null);
  }, [conversationId]);

  const wallpaperImageUrl = useMemo(() => getWallpaperImageUrl(wallpaper), [wallpaper]);

  const handleWallpaperChange = (nextWallpaper: ChatWallpaperSetting) => {
    setConversationWallpaper(conversationId, nextWallpaper);
    setWallpaper(nextWallpaper);
  };

  const handleReplyMessage = useCallback(
    (message: Message) => {
      let preview = 'Message';
      if (message.messageType === 'IMAGE') {
        preview = '📷 Photo' + (message.caption ? ` — ${message.caption}` : '');
      } else if (message.messageType === 'LOCATION') {
        preview = '📍 Location' + (message.locationLabel ? ` — ${message.locationLabel}` : '');
      } else if (message.messageType === 'DOCUMENT') {
        preview = '📄 ' + (message.caption || 'Document');
      } else if (message.decryptedContent) {
        preview = message.decryptedContent;
      } else if (message.caption) {
        preview = message.caption;
      }

      setEditTarget(null);
      setReplyTarget({
        messageId: message.id,
        senderUsername: message.senderUsername || (message.senderUserId === currentUserId ? 'You' : 'User'),
        messageType: message.messageType || 'TEXT',
        previewText: preview,
      });
    },
    [currentUserId]
  );

  const handleEditMessageTrigger = useCallback((message: Message) => {
    setReplyTarget(null);
    setEditTarget(message);
  }, []);

  const handleSubmitEdit = async (newPlaintext: string) => {
    if (!editTarget || !onEditMessage) return;
    await onEditMessage(editTarget.id, newPlaintext);
    setEditTarget(null);
  };

  const handleEnterSelectionMode = useCallback((message: Message) => {
    setSelectionMode(true);
    setSelectedMessageIds(new Set([message.id]));
  }, []);

  const handleToggleSelect = useCallback((message: Message) => {
    setSelectedMessageIds((prev) => {
      const next = new Set(prev);
      if (next.has(message.id)) {
        next.delete(message.id);
      } else {
        next.add(message.id);
      }
      if (next.size === 0) {
        setSelectionMode(false);
      }
      return next;
    });
  }, []);

  const handleExitSelectionMode = () => {
    setSelectionMode(false);
    setSelectedMessageIds(new Set());
  };

  const selectedMessages = useMemo(
    () => messages.filter((m) => selectedMessageIds.has(m.id)),
    [messages, selectedMessageIds]
  );

  const EDIT_WINDOW_MS = 15 * 60 * 1000;
  const singleSelected = selectedMessages.length === 1 ? selectedMessages[0] : null;
  const singleSelectedEditable =
    !!singleSelected &&
    singleSelected.senderUserId === currentUserId &&
    singleSelected.messageType === 'TEXT' &&
    !singleSelected.deletedForEveryone &&
    singleSelected.id > 0 &&
    Date.now() - new Date(singleSelected.sentAt).getTime() < EDIT_WINDOW_MS;

  const handleReplySelected = () => {
    if (!singleSelected) return;
    handleReplyMessage(singleSelected);
    handleExitSelectionMode();
  };

  const handleEditSelected = () => {
    if (!singleSelected || !singleSelectedEditable) return;
    handleEditMessageTrigger(singleSelected);
    handleExitSelectionMode();
  };

  const handleTogglePinSelected = () => {
    if (!singleSelected) return;
    if (singleSelected.pinnedAt) {
      onUnpinMessage?.(singleSelected.id);
    } else {
      onPinMessage?.(singleSelected.id);
    }
    handleExitSelectionMode();
  };

  const handleBulkCopy = () => {
    const texts = selectedMessages
      .filter((m) => m.messageType === 'TEXT' || !m.messageType)
      .map((m) => m.decryptedContent)
      .filter((t): t is string => !!t);
    if (texts.length > 0) {
      navigator.clipboard?.writeText(texts.join('\n'));
    }
    handleExitSelectionMode();
  };

  const handleBulkDelete = () => {
    const allOwnAndEligible = selectedMessages.every((m) => m.senderUserId === currentUserId && !m.deletedForEveryone);
    const deleteForEveryone = allOwnAndEligible
      ? window.confirm(`Delete ${selectedMessages.length} message(s) for everyone? Choose Cancel to delete only for you.`)
      : false;
    if (!deleteForEveryone && !window.confirm(`Delete ${selectedMessages.length} message(s) for you?`)) {
      return;
    }
    selectedMessages.forEach((m) => onDeleteMessage(m.id, deleteForEveryone));
    handleExitSelectionMode();
  };

  const handleBulkReact = (emoji: string) => {
    selectedMessages.forEach((m) => onReactMessage?.(m.id, emoji));
    setShowBulkReactPicker(false);
    handleExitSelectionMode();
  };

  const handleBulkStar = () => {
    const allStarred = selectedMessages.every((m) => m.starred);
    selectedMessages.forEach((m) => {
      if (allStarred) {
        onUnstarMessage?.(m.id);
      } else if (!m.starred) {
        onStarMessage?.(m.id);
      }
    });
    handleExitSelectionMode();
  };

  const handleForwardMessage = useCallback((message: Message) => {
    setForwardQueue([message]);
  }, []);

  const handleForwardSelected = () => {
    if (selectedMessages.length === 0) return;
    setForwardQueue(selectedMessages);
  };

  const handleConfirmForward = async (targetConversationIds: number[]): Promise<ForwardResult[]> => {
    if (!forwardQueue || !onForwardMessages) return [];
    const results = await onForwardMessages(forwardQueue, targetConversationIds);
    if (results.some((r) => r.success)) {
      handleExitSelectionMode();
    }
    return results;
  };

  const handleScrollToMessage = (messageId: number) => {
    const el = document.getElementById(`message-${messageId}`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.classList.add('bg-indigo-500/20', 'rounded-2xl', 'transition-all');
      setTimeout(() => {
        el.classList.remove('bg-indigo-500/20', 'rounded-2xl', 'transition-all');
      }, 1800);
    }
  };

  return (
    <div className="chat-screen flex flex-1 min-h-0 min-w-0 flex-col bg-white dark:bg-[#0f172a]">
      <header className="chat-header flex-shrink-0">
        {selectionMode ? (
          <div className="h-[60px] min-h-[60px] md:h-[68px] md:min-h-[68px] px-2 md:px-6 border-b border-slate-200/80 dark:border-slate-800/80 bg-white dark:bg-[#0f172a] flex items-center justify-between select-none">
            <div className="flex items-center gap-2 min-w-0">
              <button
                onClick={handleExitSelectionMode}
                className="p-2 text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-lg transition-colors"
                aria-label="Cancel selection"
              >
                <X className="w-5 h-5" />
              </button>
              <span className="font-semibold text-slate-900 dark:text-white text-sm">
                {selectedMessageIds.size} selected
              </span>
            </div>
            <div className="flex items-center gap-0.5 md:gap-1">
              {singleSelected && (
                <button
                  onClick={handleReplySelected}
                  className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
                  aria-label="Reply"
                  title="Reply"
                >
                  <CornerUpLeft className="w-5 h-5" />
                </button>
              )}
              {singleSelectedEditable && (
                <button
                  onClick={handleEditSelected}
                  className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
                  aria-label="Edit"
                  title="Edit"
                >
                  <Pencil className="w-5 h-5" />
                </button>
              )}
              {singleSelected && (
                <button
                  onClick={handleTogglePinSelected}
                  className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-amber-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
                  aria-label={singleSelected.pinnedAt ? 'Unpin' : 'Pin'}
                  title={singleSelected.pinnedAt ? 'Unpin' : 'Pin'}
                >
                  {singleSelected.pinnedAt ? <PinOff className="w-5 h-5" /> : <Pin className="w-5 h-5" />}
                </button>
              )}
              <div className="relative">
                <button
                  onClick={() => setShowBulkReactPicker((v) => !v)}
                  disabled={selectedMessages.length === 0}
                  className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-amber-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40"
                  aria-label="React"
                  title="React"
                >
                  <SmilePlus className="w-5 h-5" />
                </button>
                {showBulkReactPicker && (
                  <>
                    <div className="fixed inset-0 z-40" onClick={() => setShowBulkReactPicker(false)} />
                    <div className="absolute right-0 top-full mt-1 z-50 flex items-center gap-0.5 bg-slate-900 border border-slate-700 rounded-full py-1.5 px-2 shadow-2xl animate-pop-in">
                      {QUICK_REACTIONS.map((emoji) => (
                        <button
                          key={emoji}
                          type="button"
                          onClick={() => handleBulkReact(emoji)}
                          className="hover:scale-125 active:scale-90 transition-transform text-xl w-9 h-9 flex items-center justify-center rounded-full hover:bg-slate-800"
                        >
                          {emoji}
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
              <button
                onClick={handleBulkStar}
                disabled={selectedMessages.length === 0}
                className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-amber-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40"
                aria-label="Star"
                title="Star"
              >
                <Star className="w-5 h-5" />
              </button>
              <button
                onClick={handleForwardSelected}
                disabled={selectedMessages.length === 0}
                className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40"
                aria-label="Forward"
                title="Forward"
              >
                <Forward className="w-5 h-5" />
              </button>
              <button
                onClick={handleBulkCopy}
                disabled={selectedMessages.length === 0}
                className="hidden sm:flex p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40"
                aria-label="Copy"
                title="Copy"
              >
                <Copy className="w-5 h-5" />
              </button>
              <button
                onClick={handleBulkDelete}
                disabled={selectedMessages.length === 0}
                className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-rose-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40"
                aria-label="Delete"
                title="Delete"
              >
                <Trash2 className="w-5 h-5" />
              </button>
            </div>
          </div>
        ) : isGroup ? (
          <GroupChatHeader
            group={group ?? null}
            showInfoDrawer={showInfoDrawer}
            onToggleInfoDrawer={onToggleInfoDrawer}
            onBack={onBack}
          />
        ) : (
          <ChatHeader
            recipient={recipient}
            showRawCiphertext={showRawCiphertext}
            showInfoDrawer={showInfoDrawer}
            wallpaper={wallpaper}
            isMuted={isMuted}
            isTyping={isTyping}
            onToggleCiphertext={onToggleCiphertext}
            onToggleInfoDrawer={onToggleInfoDrawer}
            onWallpaperChange={handleWallpaperChange}
            onBack={onBack}
            onClearChat={onClearChat}
            onMuteChat={onMuteChat}
            onUnmuteChat={onUnmuteChat}
            onExportChat={onExportChat}
            exportingChat={exportingChat}
          />
        )}
      </header>

      {!selectionMode && pinnedMessage && !pinnedMessage.deletedForEveryone && (
        <button
          type="button"
          onClick={() => handleScrollToMessage(pinnedMessage.id)}
          className="flex-shrink-0 w-full flex items-center gap-2 px-3 md:px-6 py-1.5 bg-amber-500/10 dark:bg-amber-950/30 border-b border-amber-500/20 text-left transition-colors hover:bg-amber-500/15 select-none"
        >
          <Pin className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" />
          <span className="flex-1 min-w-0 truncate text-xs text-amber-700 dark:text-amber-300">
            {pinnedMessage.messageType === 'IMAGE'
              ? '📷 Photo' + (pinnedMessage.caption ? ` — ${pinnedMessage.caption}` : '')
              : pinnedMessage.messageType === 'LOCATION'
              ? '📍 Location'
              : pinnedMessage.messageType === 'DOCUMENT'
              ? '📄 ' + (pinnedMessage.caption || 'Document')
              : pinnedMessage.decryptedContent || 'Pinned message'}
          </span>
        </button>
      )}

      <main className="chat-messages relative min-h-0 min-w-0 flex-1 overflow-hidden">
        <ChatWallpaperBackground imageUrl={wallpaperImageUrl} isDarkMode={isDarkMode} />
        <div className="relative z-10 h-full min-h-0">
          <MessageFeed
            messages={messages}
            currentUserId={currentUserId}
            isGroup={isGroup}
            groupMembers={groupMembers}
            showRawCiphertext={showRawCiphertext}
            hasMore={hasMore}
            isLoadingOlder={isLoadingOlder}
            onLoadOlderMessages={onLoadOlderMessages}
            onDeleteMessage={onDeleteMessage}
            onReplyMessage={handleReplyMessage}
            onReactMessage={onReactMessage}
            onEditMessage={handleEditMessageTrigger}
            onPinMessage={onPinMessage}
            onUnpinMessage={onUnpinMessage}
            onStarMessage={onStarMessage}
            onUnstarMessage={onUnstarMessage}
            onForwardMessage={handleForwardMessage}
            selectionMode={selectionMode}
            selectedMessageIds={selectedMessageIds}
            onToggleSelect={handleToggleSelect}
            onEnterSelectionMode={handleEnterSelectionMode}
          />
        </div>
      </main>

      {isGroup ? (
        group && group.currentUserRole && canSendInGroup(group) ? (
          <footer className="chat-composer flex-shrink-0">
            <MessageInput
              conversationId={conversationId}
              isGroup
              group={group}
              currentUserId={currentUserId}
              replyTarget={replyTarget}
              onCancelReply={() => setReplyTarget(null)}
              editTarget={editTarget}
              onCancelEdit={() => setEditTarget(null)}
              onSubmitEdit={handleSubmitEdit}
              onOptimisticMessage={onOptimisticMessage}
              onOptimisticMessageFailed={onOptimisticMessageFailed}
              onOptimisticImageMessage={onOptimisticImageMessage}
              onOptimisticLocationMessage={onOptimisticLocationMessage}
              onOptimisticDocumentMessage={onOptimisticDocumentMessage}
              onMessageSent={onMessageSent}
            />
          </footer>
        ) : (
          <GroupComposerPlaceholder group={group ?? null} />
        )
      ) : relationship === 'CONNECTED' ? (
        <footer className="chat-composer flex-shrink-0">
          <MessageInput
            conversationId={conversationId}
            recipientUserId={recipient?.id || 0}
            currentUserId={currentUserId}
            replyTarget={replyTarget}
            onCancelReply={() => setReplyTarget(null)}
            editTarget={editTarget}
            onCancelEdit={() => setEditTarget(null)}
            onSubmitEdit={handleSubmitEdit}
            onOptimisticMessage={onOptimisticMessage}
            onOptimisticMessageFailed={onOptimisticMessageFailed}
            onOptimisticImageMessage={onOptimisticImageMessage}
            onOptimisticLocationMessage={onOptimisticLocationMessage}
            onOptimisticDocumentMessage={onOptimisticDocumentMessage}
            onMessageSent={onMessageSent}
            initialSharedMedia={initialSharedMedia}
            onSharedMediaConsumed={onSharedMediaConsumed}
          />
        </footer>
      ) : (
        recipient && (
          <ChatRelationshipGate
            recipientId={recipient.id}
            recipientName={recipient.displayName || recipient.username}
            relationship={relationship ?? 'NOT_CONNECTED'}
            sentRequest={sentRequest}
            receivedRequest={receivedRequest}
            onSendRequest={onSendConnectionRequest}
            onCancelRequest={onCancelConnectionRequest}
            onAcceptRequest={onAcceptConnectionRequest}
            onRejectRequest={onRejectConnectionRequest}
            onUnblock={onUnblockUser}
          />
        )
      )}

      {forwardQueue && (
        <ForwardMessageModal
          open={!!forwardQueue}
          messages={forwardQueue}
          conversations={conversations}
          currentUserId={currentUserId}
          onClose={() => setForwardQueue(null)}
          onForward={handleConfirmForward}
        />
      )}
    </div>
  );
};
