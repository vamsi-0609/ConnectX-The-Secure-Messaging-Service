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
} from 'lucide-react';
import { User } from '../../types';
import { ClearChatConfirmDialog } from './ClearChatConfirmDialog';
import { MuteChatModal } from './MuteChatModal';
import { UserAvatar } from '../common/UserAvatar';
import { ChatWallpaperMenu } from './ChatWallpaperMenu';
import { ChatWallpaperSetting } from '../../utils/chatWallpaper';

interface ChatHeaderProps {
  recipient: User | null;
  showRawCiphertext: boolean;
  showInfoDrawer: boolean;
  wallpaper: ChatWallpaperSetting;
  isMuted?: boolean;
  onToggleCiphertext: () => void;
  onToggleInfoDrawer: () => void;
  onWallpaperChange: (wallpaper: ChatWallpaperSetting) => void;
  onBack?: () => void;
  onClearChat?: () => Promise<void>;
  onMuteChat?: (duration: '8_HOURS' | '1_WEEK' | 'ALWAYS') => Promise<void>;
  onUnmuteChat?: () => Promise<void>;
}

export const ChatHeader: React.FC<ChatHeaderProps> = React.memo(({
  recipient,
  showRawCiphertext,
  showInfoDrawer,
  wallpaper,
  isMuted,
  onToggleCiphertext,
  onToggleInfoDrawer,
  onWallpaperChange,
  onBack,
  onClearChat,
  onMuteChat,
  onUnmuteChat,
}) => {
  const [showMenu, setShowMenu] = useState(false);
  const [menuView, setMenuView] = useState<'main' | 'wallpaper'>('main');
  const [showClearConfirm, setShowClearConfirm] = useState(false);
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [clearing, setClearing] = useState(false);

  const displayName = recipient?.displayName || recipient?.username || 'Contact';
  const username = recipient?.username || '';

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
      <div className="h-[60px] min-h-[60px] md:h-[68px] md:min-h-[68px] px-2 md:px-6 border-b border-slate-200/80 dark:border-slate-800/80 bg-white dark:bg-[#0f172a] flex items-center justify-between select-none">
        <div className="flex items-center gap-1 min-w-0 flex-1">
          {onBack && (
            <button
              onClick={onBack}
              className="md:hidden p-2 text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-lg transition-colors flex-shrink-0"
              aria-label="Back to conversations"
            >
              <ArrowLeft className="w-5 h-5" />
            </button>
          )}

          <div className="flex items-center gap-2.5 min-w-0 flex-1">
            {recipient && (
              <UserAvatar user={recipient} size="sm" className="md:w-11 md:h-11 flex-shrink-0" />
            )}
            {!recipient && (
              <div className="w-10 h-10 md:w-11 md:h-11 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center font-semibold text-white text-sm md:text-base flex-shrink-0">
                ?
              </div>
            )}

            <button
              type="button"
              onClick={onToggleInfoDrawer}
              className="min-w-0 text-left flex-1"
              disabled={!recipient}
            >
              <div className="min-w-0">
                <div className="flex items-center gap-1">
                  <h2 className="font-semibold text-slate-900 dark:text-white text-[15px] md:text-[16px] truncate leading-tight">
                    {displayName}
                  </h2>
                  {recipient && <ShieldCheck className="w-3.5 h-3.5 text-pink-400 flex-shrink-0" aria-hidden />}
                </div>
                <p className="text-[11px] md:text-xs text-slate-500 dark:text-slate-400 truncate leading-tight mt-0.5">
                  {recipient ? (
                    username ? `@${username}` : 'End-to-end encrypted'
                  ) : (
                    'Loading contact...'
                  )}
                </p>
              </div>
            </button>
          </div>
        </div>

        <div className="flex items-center gap-0.5 md:gap-1 flex-shrink-0">
          <button
            onClick={() => alert('Voice call module is scheduled for Milestone 14.')}
            className="hidden md:flex p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-500 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
            aria-label="Voice call"
          >
            <Phone className="w-5 h-5" />
          </button>

          <button
            onClick={() => alert('Video call module is scheduled for Milestone 14.')}
            className="hidden md:flex p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-500 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
            aria-label="Video call"
          >
            <Video className="w-5 h-5" />
          </button>

          <button
            onClick={onToggleCiphertext}
            className={`hidden lg:flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium border transition-colors ${
              showRawCiphertext
                ? 'bg-pink-500/10 text-pink-400 border-pink-500/30'
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
              className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
              aria-label="Chat options"
            >
              <MoreVertical className="w-5 h-5" />
            </button>

            {showMenu && (
              <>
                <div className="fixed inset-0 z-20" onClick={closeMenu} />
                <div
                  className={`absolute right-0 top-full mt-1 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-30 text-sm ${
                    menuView === 'wallpaper' ? 'w-64' : 'w-48 py-1'
                  }`}
                >
                  {menuView === 'main' ? (
                    <>
                      <button
                        onClick={() => {
                          onToggleInfoDrawer();
                          closeMenu();
                        }}
                        className={`w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 ${
                          showInfoDrawer ? 'text-indigo-500' : 'text-slate-700 dark:text-slate-200'
                        }`}
                      >
                        <Info className="w-4 h-4" /> Contact info
                      </button>
                      <button
                        onClick={() => setMenuView('wallpaper')}
                        className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
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
                          className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-indigo-500"
                        >
                          <Bell className="w-4 h-4" /> Unmute notifications
                        </button>
                      ) : (
                        <button
                          onClick={() => {
                            setShowMuteModal(true);
                            closeMenu();
                          }}
                          className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
                        >
                          <BellOff className="w-4 h-4" /> Mute notifications
                        </button>
                      )}
                      <button
                        onClick={() => {
                          onToggleCiphertext();
                          closeMenu();
                        }}
                        className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200 lg:hidden"
                      >
                        {showRawCiphertext ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                        Raw ciphertext
                      </button>
                      {onClearChat && (
                        <button
                          onClick={() => {
                            setShowClearConfirm(true);
                          }}
                          className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-red-50 dark:hover:bg-red-950/30 text-red-600 dark:text-red-400"
                        >
                          <Eraser className="w-4 h-4" /> Clear Chat
                        </button>
                      )}
                      <button
                        onClick={() => {
                          alert('Voice call module is scheduled for Milestone 14.');
                          closeMenu();
                        }}
                        className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200 md:hidden"
                      >
                        <Phone className="w-4 h-4" /> Voice call
                      </button>
                      <button
                        onClick={() => {
                          alert('Video call module is scheduled for Milestone 14.');
                          closeMenu();
                        }}
                        className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200 md:hidden"
                      >
                        <Video className="w-4 h-4" /> Video call
                      </button>
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
