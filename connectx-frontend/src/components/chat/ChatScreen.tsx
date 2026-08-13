import React, { useEffect, useMemo, useState } from 'react';
import { User, Message } from '../../types';
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
  onToggleCiphertext: () => void;
  onToggleInfoDrawer: () => void;
  onBack: () => void;
  onDeleteMessage: (messageId: number, deleteForEveryone: boolean) => void;
  onOptimisticMessage: (plaintext: string, ciphertext: string, nonce: string, recipientDeviceId: number) => void;
  onOptimisticImageMessage: (mediaId: number, caption: string | undefined, localPreviewUrl: string, mimeType: string) => void;
  onOptimisticLocationMessage: (
    latitude: number,
    longitude: number,
    locationLabel: string | undefined
  ) => void;
  onOptimisticDocumentMessage: (
    mediaId: number,
    filename: string,
    mimeType: string,
    fileSizeBytes: number
  ) => void;
  onMessageSent?: () => void;
  onClearChat?: () => Promise<void>;
}

export const ChatScreen: React.FC<ChatScreenProps> = ({
  recipient,
  conversationId,
  messages,
  currentUserId,
  showRawCiphertext,
  showInfoDrawer,
  isDarkMode,
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
}) => {
  const [wallpaper, setWallpaper] = useState<ChatWallpaperSetting>(() =>
    getConversationWallpaper(conversationId)
  );

  useEffect(() => {
    setWallpaper(getConversationWallpaper(conversationId));
  }, [conversationId]);

  const wallpaperImageUrl = useMemo(() => getWallpaperImageUrl(wallpaper), [wallpaper]);

  const handleWallpaperChange = (nextWallpaper: ChatWallpaperSetting) => {
    setConversationWallpaper(conversationId, nextWallpaper);
    setWallpaper(nextWallpaper);
  };

  return (
    <div className="chat-screen flex flex-1 min-h-0 min-w-0 flex-col bg-white dark:bg-[#0f172a]">
      <header className="chat-header flex-shrink-0">
        <ChatHeader
          recipient={recipient}
          showRawCiphertext={showRawCiphertext}
          showInfoDrawer={showInfoDrawer}
          wallpaper={wallpaper}
          onToggleCiphertext={onToggleCiphertext}
          onToggleInfoDrawer={onToggleInfoDrawer}
          onWallpaperChange={handleWallpaperChange}
          onBack={onBack}
          onClearChat={onClearChat}
        />
      </header>

      <main className="chat-messages relative min-h-0 min-w-0 flex-1 overflow-hidden">
        <ChatWallpaperBackground imageUrl={wallpaperImageUrl} isDarkMode={isDarkMode} />
        <div className="relative z-10 h-full min-h-0">
          <MessageFeed
            messages={messages}
            currentUserId={currentUserId}
            showRawCiphertext={showRawCiphertext}
            onDeleteMessage={onDeleteMessage}
          />
        </div>
      </main>

      <footer className="chat-composer flex-shrink-0">
        <MessageInput
          conversationId={conversationId}
          recipientUserId={recipient?.id || 0}
          currentUserId={currentUserId}
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
