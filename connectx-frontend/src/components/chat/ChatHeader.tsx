import React, { useState } from 'react';
import {
  ShieldCheck,
  Phone,
  Video,
  Eye,
  EyeOff,
  ArrowLeft,
  MoreVertical,
  Info,
  Eraser,
  Palette,
  BellOff,
  Bell,
  Download,
  Loader2,
} from 'lucide-react';
import { User } from '../../types';
import { ClearChatConfirmDialog } from './ClearChatConfirmDialog';
import { MuteChatModal } from './MuteChatModal';
import { UserAvatar } from '../common/UserAvatar';
import { ChatWallpaperMenu } from './ChatWallpaperMenu';
import { ChatWallpaperSetting } from '../../utils/chatWallpaper';
import { formatLastSeen } from '../../utils/presence';

interface ChatHeaderProps {
  recipient: User | null;
  showRawCiphertext: boolean;
  showInfoDrawer: boolean;
  wallpaper: ChatWallpaperSetting;
  isMuted?: boolean;
  isTyping?: boolean;
  onToggleCiphertext: () => void;
  onToggleInfoDrawer: () => void;
  onWallpaperChange: (wallpaper: ChatWallpaperSetting) => void;
  onBack?: () => void;
  onClearChat?: () => Promise<void>;
  onMuteChat?: (duration: '8_HOURS' | '1_WEEK' | 'ALWAYS') => Promise<void>;
  onUnmuteChat?: () => Promise<void>;
  onExportChat?: () => Promise<void>;
  exportingChat?: boolean;
}

export const ChatHeader: React.FC<ChatHeaderProps> = React.memo(({
  recipient,
  showRawCiphertext,
  showInfoDrawer,
  wallpaper,
  isMuted,
  isTyping,
  onToggleCiphertext,
  onToggleInfoDrawer,
  onWallpaperChange,
  onBack,
  onClearChat,
  onMuteChat,
  onUnmuteChat,
  onExportChat,
  exportingChat,
}) => {
  const [showMenu, setShowMenu] = useState(false);
  const [menuView, setMenuView] = useState<'main' | 'wallpaper'>('main');
  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [clearing, setClearing] = useState(false);

  const displayName = recipient?.displayName || recipient?.username || 'Contact';
  const username = recipient?.username || '';
  const lastSeenText = recipient ? formatLastSeen(recipient.lastSeenAt) : null;
  const subtitle = isTyping
    ? 'typing...'
    : recipient?.status === 'ONLINE'
    ? 'Online'
    : lastSeenText || (username ? `@${username}` : 'End-to-end encrypted');

  const closeMenu = () => {
    setShowMenu(false);
    setMenuView('main');
  };

  const handleConfirmClear = async () => {
    if (!onClearChat || clearing) return;

    setClearing(true);
    try {
      await onClearChat();
      setShowClearConfirm(false);
      closeMenu();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to clear chat';
      alert('Failed to clear chat: ' + message);
    } finally {
      setClearing(false);
    }
  };

  return (
    <>
      <div className="h-[56px] sm:h-[60px] md:h-[68px] min-h-[56px] sm:min-h-[60px] md:min-h-[68px] px-2 sm:px-4 md:px-6 border-b border-slate-200/90 dark:border-slate-800/80 bg-white/95 dark:bg-[#0a0e1a]/95 backdrop-blur-sm flex items-center justify-between gap-1.5 sm:gap-2 select-none z-20 flex-shrink-0">
        {/* Left: Back button + Identity */}
        <div className="flex items-center gap-1 sm:gap-2 min-w-0 flex-1">
          {onBack && (
            <button
              onClick={onBack}
              className="md:hidden w-9 h-9 sm:w-10 sm:h-10 -ml-1 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95 transition-all duration-150 motion-reduce:transition-none flex-shrink-0 cursor-pointer"
              aria-label="Back to conversations"
            >
              <ArrowLeft className="w-5 h-5" />
            </button>
          )}

          <button
            type="button"
            onClick={onToggleInfoDrawer}
            disabled={!recipient}
            className="flex items-center gap-2 sm:gap-2.5 min-w-0 flex-1 text-left cursor-pointer group"
            aria-label="View contact info"
          >
            {recipient ? (
              <UserAvatar
                user={recipient}
                size="sm"
                className="w-9 h-9 sm:w-10 sm:h-10 md:w-11 md:h-11 flex-shrink-0 group-hover:ring-2 group-hover:ring-violet-500/40 transition-all rounded-full"
                passive
              />
            ) : (
              <div className="w-9 h-9 sm:w-10 sm:h-10 md:w-11 md:h-11 rounded-full bg-gradient-to-tr from-violet-600 to-indigo-500 flex items-center justify-center font-semibold text-white text-sm md:text-base flex-shrink-0">
                ?
              </div>
            )}

            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1 min-w-0">
                <h2 className="font-bold text-slate-900 dark:text-white text-[14px] sm:text-[15px] md:text-[16px] truncate leading-tight">
                  {displayName}
                </h2>
                {recipient && (
                  <ShieldCheck
                    className="w-3.5 h-3.5 text-violet-500 dark:text-violet-400 flex-shrink-0"
                    aria-hidden
                  />
                )}
              </div>
              <p
                className={`text-[11px] sm:text-xs truncate leading-tight mt-0.5 ${
                  isTyping
                    ? 'text-violet-600 dark:text-violet-400 font-medium'
                    : 'text-slate-500 dark:text-slate-400'
                }`}
              >
                {recipient ? subtitle : 'Loading contact...'}
              </p>
            </div>
          </button>
        </div>

        {/* Right: Actions (Phone, Video, Cipher, More Menu) */}
        <div className="flex items-center gap-0.5 sm:gap-1 flex-shrink-0">
          <button
            onClick={() => alert('Audio calling is scheduled for a future ConnectX release.')}
            className="w-9 h-9 sm:w-10 sm:h-10 flex items-center justify-center text-violet-600 dark:text-violet-400 hover:text-violet-700 dark:hover:text-violet-300 hover:bg-violet-500/10 active:scale-95 rounded-xl transition-all duration-150 motion-reduce:transition-none cursor-pointer"
            aria-label="Audio call"
            title="Audio call"
          >
            <Phone className="w-5 h-5" />
          </button>

          <button
            onClick={() => alert('Video calling is scheduled for a future ConnectX release.')}
            className="w-9 h-9 sm:w-10 sm:h-10 flex items-center justify-center text-violet-600 dark:text-violet-400 hover:text-violet-700 dark:hover:text-violet-300 hover:bg-violet-500/10 active:scale-95 rounded-xl transition-all duration-150 motion-reduce:transition-none cursor-pointer"
            aria-label="Video call"
            title="Video call"
          >
            <Video className="w-5 h-5" />
          </button>

          <button
            onClick={onToggleCiphertext}
            className={`hidden lg:flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium border transition-colors cursor-pointer ${
              showRawCiphertext
                ? 'bg-violet-500/10 text-violet-500 dark:text-violet-400 border-violet-500/30'
                : 'text-slate-500 dark:text-slate-400 border-transparent hover:bg-slate-100 dark:hover:bg-slate-800/60'
            }`}
            title="Toggle raw ciphertext"
          >
            {showRawCiphertext ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
            <span>Cipher</span>
          </button>

          <div className="relative">
            <button
              onClick={() => setShowMenu(!showMenu)}
              className={`w-9 h-9 sm:w-10 sm:h-10 flex items-center justify-center rounded-xl transition-all duration-150 motion-reduce:transition-none cursor-pointer ${
                showMenu
                  ? 'text-violet-600 dark:text-violet-400 bg-slate-100 dark:bg-slate-800'
                  : 'text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95'
              }`}
              aria-label="Chat options"
            >
              <MoreVertical className="w-5 h-5" />
            </button>

            {showMenu && (
              <>
                <div className="fixed inset-0 z-30" onClick={closeMenu} />
                <div
                  className={`absolute right-0 top-full mt-1.5 bg-white/95 dark:bg-[#0c101c]/95 border border-slate-200/90 dark:border-slate-800/90 rounded-xl shadow-xl z-40 text-xs sm:text-sm backdrop-blur-sm animate-pop-in select-none ${
                    menuView === 'wallpaper' ? 'w-64' : 'w-48 sm:w-52 py-1 max-w-[calc(100vw-1rem)]'
                  }`}
                >
                  {menuView === 'main' ? (
                    <>
                      <button
                        onClick={() => {
                          onToggleInfoDrawer();
                          closeMenu();
                        }}
                        className={`w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 transition-colors cursor-pointer ${
                          showInfoDrawer
                            ? 'text-violet-600 dark:text-violet-400 font-medium'
                            : 'text-slate-700 dark:text-slate-200'
                        }`}
                      >
                        <Info className="w-4 h-4" /> Contact info
                      </button>
                      <button
                        onClick={() => setMenuView('wallpaper')}
                        className="w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 transition-colors cursor-pointer"
                      >
                        <Palette className="w-4 h-4" /> Chat background
                      </button>
                      {isMuted ? (
                        <button
                          onClick={async () => {
                            if (onUnmuteChat) {
                              await onUnmuteChat();
                            }
                            closeMenu();
                          }}
                          className="w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 text-violet-600 dark:text-violet-400 font-medium transition-colors cursor-pointer"
                        >
                          <Bell className="w-4 h-4" /> Unmute notifications
                        </button>
                      ) : (
                        <button
                          onClick={() => {
                            setShowMuteModal(true);
                            closeMenu();
                          }}
                          className="w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 transition-colors cursor-pointer"
                        >
                          <BellOff className="w-4 h-4" /> Mute notifications
                        </button>
                      )}
                      <button
                        onClick={() => {
                          onToggleCiphertext();
                          closeMenu();
                        }}
                        className="w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 lg:hidden transition-colors cursor-pointer"
                      >
                        {showRawCiphertext ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                        Raw ciphertext
                      </button>
                      {onExportChat && (
                        <button
                          onClick={async () => {
                            await onExportChat();
                            closeMenu();
                          }}
                          disabled={exportingChat}
                          className="w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 disabled:opacity-60 transition-colors cursor-pointer"
                        >
                          {exportingChat ? (
                            <Loader2 className="w-4 h-4 animate-spin" />
                          ) : (
                            <Download className="w-4 h-4" />
                          )}
                          {exportingChat ? 'Preparing export...' : 'Export Chat'}
                        </button>
                      )}
                      {onClearChat && (
                        <button
                          onClick={() => {
                            setShowClearConfirm(true);
                          }}
                          className="w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-rose-50 dark:hover:bg-rose-950/30 text-rose-600 dark:text-rose-400 transition-colors cursor-pointer"
                        >
                          <Eraser className="w-4 h-4" /> Clear Chat
                        </button>
                      )}
                    </>
                  ) : (
                    <ChatWallpaperMenu
                      selected={wallpaper}
                      onSelect={(setting) => {
                        onWallpaperChange(setting);
                        closeMenu();
                      }}
                      onBack={() => setMenuView('main')}
                    />
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      {showClearConfirm && (
        <ClearChatConfirmDialog
          contactName={displayName}
          clearing={clearing}
          onCancel={() => {
            if (!clearing) {
              setShowClearConfirm(false);
            }
          }}
          onConfirm={handleConfirmClear}
        />
      )}

      {showMuteModal && (
        <MuteChatModal
          open={showMuteModal}
          contactName={displayName}
          onClose={() => setShowMuteModal(false)}
          onConfirmMute={async (duration) => {
            if (onMuteChat) {
              await onMuteChat(duration);
            }
          }}
        />
      )}
    </>
  );
});

ChatHeader.displayName = 'ChatHeader';
