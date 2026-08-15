import React, { useEffect, useMemo, useState } from 'react';
import { User, Message, ReplyTarget } from '../../types';
import { ChatHeader } from './ChatHeader';
import { MessageFeed } from './MessageFeed';
import { MessageInput } from './MessageInput';
import { ChatWallpaperBackground } from './ChatWallpaperBackground';
import {
  ChatWallpaperSetting,
  getConversationWallpaper,
  getWallpaperImageUrl,
  setConversationWallpaper,
} from '../../utils/chatWallpaper';

interface ChatScreenProps {
  recipient: User | null;
  conversationId: number;
  messages: Message[];
  currentUserId: number;
  showRawCiphertext: boolean;
  showInfoDrawer: boolean;
  isDarkMode: boolean;
  isMuted?: boolean;
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
  onReactMessage?: (messageId: number, reaction: string) => Promise<void>;
}

export const ChatScreen: React.FC<ChatScreenProps> = ({
  recipient,
  conversationId,
  messages,
  currentUserId,
  showRawCiphertext,
  showInfoDrawer,
  isDarkMode,
  isMuted,
  hasMore,
  isLoadingOlder,
  onLoadOlderMessages,
  onToggleCiphertext,
  onToggleInfoDrawer,
  onBack,
  onDeleteMessage,
  onOptimisticMessage,
  onOptimisticImageMessage,
  onOptimisticLocationMessage,
  onOptimisticDocumentMessage,
  onMessageSent,
  onClearChat,
  onMuteChat,
  onUnmuteChat,
  onReactMessage,
}) => {
  const [wallpaper, setWallpaper] = useState<ChatWallpaperSetting>(() =>
    getConversationWallpaper(conversationId)
  );
  const [replyTarget, setReplyTarget] = useState<ReplyTarget | null>(null);

  useEffect(() => {
    setWallpaper(getConversationWallpaper(conversationId));
    setReplyTarget(null);
  }, [conversationId]);

  const wallpaperImageUrl = useMemo(() => getWallpaperImageUrl(wallpaper), [wallpaper]);

  const handleWallpaperChange = (nextWallpaper: ChatWallpaperSetting) => {
    setConversationWallpaper(conversationId, nextWallpaper);
    setWallpaper(nextWallpaper);
  };

  const handleReplyMessage = (message: Message) => {
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

    setReplyTarget({
      messageId: message.id,
      senderUsername: message.senderUsername || (message.senderUserId === currentUserId ? 'You' : 'User'),
      messageType: message.messageType || 'TEXT',
      previewText: preview,
    });
  };

  return (
    <div className="chat-screen flex flex-1 min-h-0 min-w-0 flex-col bg-white dark:bg-[#0f172a]">
      <header className="chat-header flex-shrink-0">
        <ChatHeader
          recipient={recipient}
          showRawCiphertext={showRawCiphertext}
          showInfoDrawer={showInfoDrawer}
          wallpaper={wallpaper}
          isMuted={isMuted}
          onToggleCiphertext={onToggleCiphertext}
          onToggleInfoDrawer={onToggleInfoDrawer}
          onWallpaperChange={handleWallpaperChange}
          onBack={onBack}
          onClearChat={onClearChat}
          onMuteChat={onMuteChat}
          onUnmuteChat={onUnmuteChat}
        />
      </header>

      <main className="chat-messages relative min-h-0 min-w-0 flex-1 overflow-hidden">
        <ChatWallpaperBackground imageUrl={wallpaperImageUrl} isDarkMode={isDarkMode} />
        <div className="relative z-10 h-full min-h-0">
          <MessageFeed
            messages={messages}
            currentUserId={currentUserId}
            showRawCiphertext={showRawCiphertext}
            hasMore={hasMore}
            isLoadingOlder={isLoadingOlder}
            onLoadOlderMessages={onLoadOlderMessages}
            onDeleteMessage={onDeleteMessage}
            onReplyMessage={handleReplyMessage}
            onReactMessage={onReactMessage}
          />
        </div>
      </main>

      <footer className="chat-composer flex-shrink-0">
        <MessageInput
          conversationId={conversationId}
          recipientUserId={recipient?.id || 0}
          currentUserId={currentUserId}
          replyTarget={replyTarget}
          onCancelReply={() => setReplyTarget(null)}
          onOptimisticMessage={onOptimisticMessage}
          onOptimisticImageMessage={onOptimisticImageMessage}
          onOptimisticLocationMessage={onOptimisticLocationMessage}
          onOptimisticDocumentMessage={onOptimisticDocumentMessage}
          onMessageSent={onMessageSent}
        />
      </footer>
    </div>
  );
};
