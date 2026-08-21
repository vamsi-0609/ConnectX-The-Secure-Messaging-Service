import React, { useRef, useState } from 'react';
import {
  ShieldCheck,
  X,
  ArrowLeft,
  UserMinus,
  UserPlus,
  Users,
  Check,
  XCircle,
  Clock,
  Loader2,
  Lock,
  MessageCircle,
  Phone,
  Video,
  Bell,
  BellOff,
  Ban,
  Info,
} from 'lucide-react';
import { User, ConnectionRequestDto, RelationshipStatus } from '../../types';
import { UserAvatar } from '../common/UserAvatar';
import { BlockUserConfirmDialog } from './BlockUserConfirmDialog';
import { RemoveConnectionConfirmDialog } from './RemoveConnectionConfirmDialog';
import { MuteChatModal } from './MuteChatModal';

interface ContactInfoDrawerProps {
  recipient: User | null;
  onClose: () => void;
  isBlocked?: boolean;
  onBlock?: (userId: number) => Promise<void>;
  onUnblock?: (userId: number) => Promise<void>;
  relationship?: RelationshipStatus;
  sentRequest?: ConnectionRequestDto;
  receivedRequest?: ConnectionRequestDto;
  onRemoveConnection?: (userId: number) => Promise<void>;
  onSendRequest?: (userId: number) => Promise<void>;
  onCancelRequest?: (requestId: number, userId: number) => Promise<void>;
  onAcceptRequest?: (requestId: number, userId: number) => Promise<void>;
  onRejectRequest?: (requestId: number, userId: number) => Promise<void>;
  isMuted?: boolean;
  onMuteChat?: (duration: '8_HOURS' | '1_WEEK' | 'ALWAYS') => Promise<void>;
  onUnmuteChat?: () => Promise<void>;
}

export const ContactInfoDrawer: React.FC<ContactInfoDrawerProps> = ({
  recipient,
  onClose,
  isBlocked = false,
  onBlock,
  onUnblock,
  relationship,
  sentRequest,
  receivedRequest,
  onRemoveConnection,
  onSendRequest,
  onCancelRequest,
  onAcceptRequest,
  onRejectRequest,
  isMuted = false,
  onMuteChat,
  onUnmuteChat,
}) => {
  const [showBlockConfirm, setShowBlockConfirm] = useState(false);
  const [blockActionBusy, setBlockActionBusy] = useState(false);
  const [showRemoveConfirm, setShowRemoveConfirm] = useState(false);
  const [removeActionBusy, setRemoveActionBusy] = useState(false);
  const [connectionActionBusy, setConnectionActionBusy] = useState(false);
  const [showMuteModal, setShowMuteModal] = useState(false);
  const [callNotice, setCallNotice] = useState<string | null>(null);

  // Guards against a real UI race: the Remove-confirmation dialog and (on mobile, where this
  // drawer is full-screen) the Connect button that mounts in its place afterward can occupy
  // overlapping screen coordinates, so the second tap of a fast double-tap/double-click on
  // "Remove" can land on the freshly-mounted "Connect" button and fire a genuine, unwanted
  // sendRequest. Recording *when* and *for whom* a removal just completed lets handleAddConnection
  // ignore only that specific click-through, not connect attempts in general.
  const justRemovedRef = useRef<{ userId: number; at: number } | null>(null);
  const REMOVE_CONNECT_GUARD_MS = 600;

  if (!recipient) return null;

  const displayName = recipient.displayName || recipient.username;

  const handleAudioCallClick = () => {
    setCallNotice('Audio calling is being prepared for ConnectX.');
    setTimeout(() => setCallNotice(null), 3500);
  };

  const handleVideoCallClick = () => {
    setCallNotice('Video calling will be available in a future ConnectX release.');
    setTimeout(() => setCallNotice(null), 3500);
  };

  const handleMuteToggle = async () => {
    if (isMuted) {
      if (onUnmuteChat) {
        await onUnmuteChat();
      }
    } else {
      setShowMuteModal(true);
    }
  };

  const handleConfirmBlock = async () => {
    if (!onBlock) return;
    setBlockActionBusy(true);
    try {
      await onBlock(recipient.id);
      setShowBlockConfirm(false);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to block user';
      alert(message);
    } finally {
      setBlockActionBusy(false);
    }
  };

  const handleUnblock = async () => {
    if (!onUnblock) return;
    setBlockActionBusy(true);
    try {
      await onUnblock(recipient.id);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to unblock user';
      alert(message);
    } finally {
      setBlockActionBusy(false);
    }
  };

  const handleConfirmRemoveConnection = async () => {
    if (!onRemoveConnection) return;
    setRemoveActionBusy(true);
    try {
      await onRemoveConnection(recipient.id);
      justRemovedRef.current = { userId: recipient.id, at: Date.now() };
      setShowRemoveConfirm(false);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to remove connection';
      alert(message);
    } finally {
      setRemoveActionBusy(false);
    }
  };

  const handleAddConnection = async () => {
    if (!onSendRequest || connectionActionBusy) return;
    // Swallow only a click-through immediately following a removal of this exact user -- a
    // deliberate Connect click either targets a different user (userId won't match) or arrives
    // well after the guard window (at won't match), so neither is affected.
    const justRemoved = justRemovedRef.current;
    if (justRemoved && justRemoved.userId === recipient.id && Date.now() - justRemoved.at < REMOVE_CONNECT_GUARD_MS) {
      return;
    }
    setConnectionActionBusy(true);
    try {
      await onSendRequest(recipient.id);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to send connection request';
      alert(message);
    } finally {
      setConnectionActionBusy(false);
    }
  };

  const handleWithdrawRequest = async () => {
    if (!onCancelRequest || !sentRequest || connectionActionBusy) return;
    setConnectionActionBusy(true);
    try {
      await onCancelRequest(sentRequest.id, recipient.id);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to withdraw request';
      alert(message);
    } finally {
      setConnectionActionBusy(false);
    }
  };

  const handleAcceptRequest = async () => {
    if (!onAcceptRequest || !receivedRequest || connectionActionBusy) return;
    setConnectionActionBusy(true);
    try {
      await onAcceptRequest(receivedRequest.id, recipient.id);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to accept request';
      alert(message);
    } finally {
      setConnectionActionBusy(false);
    }
  };

  const handleRejectRequest = async () => {
    if (!onRejectRequest || !receivedRequest || connectionActionBusy) return;
    setConnectionActionBusy(true);
    try {
      await onRejectRequest(receivedRequest.id, recipient.id);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to reject request';
      alert(message);
    } finally {
      setConnectionActionBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-[380px] lg:w-[420px] xl:w-[460px] h-full bg-white dark:bg-[#080b12] border-l border-slate-200/90 dark:border-slate-800/80 flex flex-col flex-shrink-0 transition-colors duration-200 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none relative">
      {/* Header */}
      <div className="h-16 px-4 border-b border-slate-200/80 dark:border-slate-800/80 flex items-center justify-between flex-shrink-0 bg-slate-50/50 dark:bg-slate-950/30">
        <div className="flex items-center gap-2">
          <button
            onClick={onClose}
            className="p-2 -ml-1 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95 transition-all cursor-pointer"
            aria-label="Back"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <h3 className="font-bold text-base md:text-[17px] text-slate-900 dark:text-white">
            Contact Info
          </h3>
        </div>
      </div>

      {/* Main Content Scroll Area */}
      <div className="p-4 sm:p-5 lg:p-6 space-y-4 flex-1 flex flex-col">
        {/* Profile Header Avatar & Identity */}
        <div className="text-center pt-2 pb-1 space-y-3">
          <div className="relative inline-block mx-auto">
            <div className="rounded-full p-1 ring-2 ring-violet-500/80 shadow-xl bg-slate-100 dark:bg-[#080b12]">
              <UserAvatar user={recipient} size="xl" className="w-24 h-24 sm:w-28 sm:h-28" />
            </div>
            <span
              className="absolute bottom-1 right-1 w-7 h-7 rounded-full bg-violet-600 text-white flex items-center justify-center ring-2 ring-white dark:ring-[#080b12] shadow-md"
              title="End-to-End Encrypted Contact"
            >
              <ShieldCheck className="w-4 h-4" />
            </span>
          </div>

          <div>
            <h2 className="text-xl font-bold tracking-tight text-slate-900 dark:text-white">
              {displayName}
            </h2>
            <p className="text-sm font-semibold text-violet-600 dark:text-violet-400 mt-0.5">
              @{recipient.username}
            </p>
          </div>
        </div>

        {/* Quick Action Row */}
        <div className="grid grid-cols-4 gap-2 p-2 sm:p-2.5 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80">
          {/* Message */}
          <button
            onClick={onClose}
            className="flex flex-col items-center justify-center gap-1.5 p-2 sm:p-2.5 rounded-xl hover:bg-slate-200/60 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 hover:text-slate-900 dark:hover:text-white transition-all active:scale-95 cursor-pointer"
            title="Open conversation"
            aria-label="Open conversation"
          >
            <div className="w-9 h-9 rounded-full bg-slate-200/70 dark:bg-slate-800/80 flex items-center justify-center text-slate-700 dark:text-slate-300">
              <MessageCircle className="w-4.5 h-4.5" />
            </div>
            <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300">
              Message
            </span>
          </button>

          {/* Audio Call */}
          <button
            onClick={handleAudioCallClick}
            className="flex flex-col items-center justify-center gap-1.5 p-2 sm:p-2.5 rounded-xl hover:bg-slate-200/60 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 hover:text-slate-900 dark:hover:text-white transition-all active:scale-95 cursor-pointer"
            title="Audio Call"
            aria-label="Audio Call"
          >
            <div className="w-9 h-9 rounded-full bg-slate-200/70 dark:bg-slate-800/80 flex items-center justify-center text-slate-700 dark:text-slate-300">
              <Phone className="w-4.5 h-4.5" />
            </div>
            <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300">
              Audio Call
            </span>
          </button>

          {/* Video Call */}
          <button
            onClick={handleVideoCallClick}
            className="flex flex-col items-center justify-center gap-1.5 p-2 sm:p-2.5 rounded-xl hover:bg-slate-200/60 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 hover:text-slate-900 dark:hover:text-white transition-all active:scale-95 cursor-pointer"
            title="Video Call"
            aria-label="Video Call"
          >
            <div className="w-9 h-9 rounded-full bg-slate-200/70 dark:bg-slate-800/80 flex items-center justify-center text-slate-700 dark:text-slate-300">
              <Video className="w-4.5 h-4.5" />
            </div>
            <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300">
              Video Call
            </span>
          </button>

          {/* Mute Notifications */}
          <button
            onClick={handleMuteToggle}
            className="flex flex-col items-center justify-center gap-1.5 p-2 sm:p-2.5 rounded-xl hover:bg-slate-200/60 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 hover:text-slate-900 dark:hover:text-white transition-all active:scale-95 cursor-pointer"
            title={isMuted ? 'Unmute Notifications' : 'Mute Notifications'}
            aria-label={isMuted ? 'Unmute Notifications' : 'Mute Notifications'}
          >
            <div
              className={`w-9 h-9 rounded-full flex items-center justify-center transition-colors ${
                isMuted
                  ? 'bg-violet-600/20 text-violet-400'
                  : 'bg-slate-200/70 dark:bg-slate-800/80 text-slate-700 dark:text-slate-300'
              }`}
            >
              {isMuted ? <BellOff className="w-4.5 h-4.5" /> : <Bell className="w-4.5 h-4.5" />}
            </div>
            <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300 text-center leading-tight truncate max-w-full">
              {isMuted ? 'Unmute' : 'Mute Notifications'}
            </span>
          </button>
        </div>

        {/* Connection Section */}
        {relationship === 'CONNECTED' && (
          <div className="p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 flex items-center justify-between gap-3 relative overflow-hidden">
            <div className="flex items-center gap-3 min-w-0">
              <div className="relative flex-shrink-0">
                <div className="w-11 h-11 rounded-full bg-violet-500/10 dark:bg-violet-500/15 border border-violet-500/25 flex items-center justify-center text-violet-600 dark:text-violet-400">
                  <Users className="w-5 h-5" />
                </div>
                <span className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-emerald-500 ring-2 ring-white dark:ring-[#0c101c]" />
              </div>
              <div className="min-w-0">
                <h4 className="text-sm font-bold text-slate-900 dark:text-white truncate">
                  {displayName}
                </h4>
                <p className="text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                  Connected
                </p>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 truncate">
                  You are connected with {displayName}
                </p>
              </div>
            </div>

            {onRemoveConnection && (
              <button
                onClick={() => setShowRemoveConfirm(true)}
                disabled={removeActionBusy}
                className="px-3.5 py-1.5 rounded-full text-xs font-medium text-rose-600 dark:text-rose-400 border border-rose-500/30 hover:bg-rose-500/10 transition-colors disabled:opacity-50 flex items-center gap-1.5 flex-shrink-0 cursor-pointer"
              >
                {removeActionBusy ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <UserMinus className="w-3.5 h-3.5" />
                )}
                <span>Remove</span>
              </button>
            )}
          </div>
        )}

        {(relationship === 'NOT_CONNECTED' || relationship === 'LEGACY_CHAT') && onSendRequest && (
          <div className="p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-11 h-11 rounded-full bg-slate-100 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 flex items-center justify-center text-slate-500 dark:text-slate-400 flex-shrink-0">
                <UserPlus className="w-5 h-5" />
              </div>
              <div className="min-w-0">
                <h4 className="text-sm font-bold text-slate-900 dark:text-white truncate">
                  {displayName}
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Not Connected
                </p>
              </div>
            </div>
            <button
              onClick={handleAddConnection}
              disabled={connectionActionBusy}
              className="px-4 py-2 rounded-xl text-xs font-semibold bg-violet-600 hover:bg-violet-500 text-white transition-colors disabled:opacity-50 shadow-sm flex items-center gap-1.5 flex-shrink-0 cursor-pointer"
            >
              {connectionActionBusy ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <UserPlus className="w-3.5 h-3.5" />
              )}
              <span>Connect</span>
            </button>
          </div>
        )}

        {relationship === 'REQUEST_SENT' && sentRequest && onCancelRequest && (
          <div className="p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-11 h-11 rounded-full bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-500 flex-shrink-0">
                <Clock className="w-5 h-5" />
              </div>
              <div className="min-w-0">
                <h4 className="text-sm font-bold text-slate-900 dark:text-white truncate">
                  {displayName}
                </h4>
                <p className="text-xs font-medium text-amber-500">
                  Request Pending
                </p>
              </div>
            </div>
            <button
              onClick={handleWithdrawRequest}
              disabled={connectionActionBusy}
              className="px-3.5 py-1.5 rounded-xl text-xs font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50 flex items-center gap-1.5 flex-shrink-0 cursor-pointer"
            >
              {connectionActionBusy && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
              <span>Withdraw</span>
            </button>
          </div>
        )}

        {relationship === 'REQUEST_RECEIVED' && receivedRequest && onAcceptRequest && onRejectRequest && (
          <div className="p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 space-y-3">
            <div className="flex items-center gap-3 min-w-0">
              <div className="w-11 h-11 rounded-full bg-violet-500/10 border border-violet-500/20 flex items-center justify-center text-violet-500 flex-shrink-0">
                <UserPlus className="w-5 h-5" />
              </div>
              <div className="min-w-0">
                <h4 className="text-sm font-bold text-slate-900 dark:text-white truncate">
                  {displayName}
                </h4>
                <p className="text-xs font-medium text-violet-600 dark:text-violet-400">
                  Connection Request Received
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={handleAcceptRequest}
                disabled={connectionActionBusy}
                className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white transition-colors disabled:opacity-50 shadow-sm cursor-pointer"
              >
                {connectionActionBusy ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <Check className="w-3.5 h-3.5" />
                )}
                <span>Accept</span>
              </button>
              <button
                onClick={handleRejectRequest}
                disabled={connectionActionBusy}
                className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50 cursor-pointer"
              >
                {connectionActionBusy ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <XCircle className="w-3.5 h-3.5" />
                )}
                <span>Reject</span>
              </button>
            </div>
          </div>
        )}

        {/* Block Section */}
        {(onBlock || onUnblock) && (
          <div className="p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-rose-500/20 dark:border-rose-900/40 text-center space-y-1">
            <div className="w-9 h-9 rounded-full bg-rose-500/10 flex items-center justify-center text-rose-500 mx-auto mb-2">
              <Ban className="w-4.5 h-4.5" />
            </div>
            {isBlocked ? (
              <div>
                <button
                  onClick={handleUnblock}
                  disabled={blockActionBusy}
                  className="text-sm font-semibold text-violet-600 dark:text-violet-400 hover:underline cursor-pointer transition-colors"
                >
                  Unblock {displayName}
                </button>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                  Allow messages and calls from this user.
                </p>
              </div>
            ) : (
              <div>
                <button
                  onClick={() => setShowBlockConfirm(true)}
                  disabled={blockActionBusy}
                  className="text-sm font-semibold text-rose-600 dark:text-rose-400 hover:underline cursor-pointer transition-colors"
                >
                  Block {displayName}
                </button>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                  You won't receive messages or calls from this user.
                </p>
              </div>
            )}
          </div>
        )}

        {/* End-to-End Encryption Section (Bottom) */}
        <div className="mt-auto p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-violet-500/20 dark:border-violet-800/30 flex items-center gap-3.5 relative overflow-hidden flex-shrink-0">
          <div className="w-11 h-11 rounded-full bg-violet-600/15 border border-violet-500/30 text-violet-500 dark:text-violet-400 flex items-center justify-center flex-shrink-0">
            <Lock className="w-5 h-5" />
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <h4 className="text-sm font-semibold text-slate-900 dark:text-white">
                End-to-End Encrypted
              </h4>
              <Lock className="w-3.5 h-3.5 text-violet-500 dark:text-violet-400 flex-shrink-0" />
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mt-0.5">
              Messages and media in this chat are secured with end-to-end encryption.
            </p>
          </div>
        </div>
      </div>

      {/* Contextual Feedback Toast */}
      {callNotice && (
        <div className="fixed sm:absolute bottom-6 left-4 right-4 z-50 p-3 rounded-xl bg-slate-900/95 dark:bg-[#0c101c]/95 border border-violet-500/30 text-white text-xs font-medium shadow-2xl flex items-center justify-between gap-3 animate-pop-in backdrop-blur-md">
          <div className="flex items-center gap-2 text-violet-300">
            <Info className="w-4 h-4 text-violet-400 flex-shrink-0" />
            <span>{callNotice}</span>
          </div>
          <button
            onClick={() => setCallNotice(null)}
            className="p-1 text-slate-400 hover:text-white rounded-lg transition-colors cursor-pointer"
            aria-label="Dismiss"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      {/* Confirmation Modals */}
      {showBlockConfirm && (
        <BlockUserConfirmDialog
          contactName={displayName}
          blocking={blockActionBusy}
          onCancel={() => setShowBlockConfirm(false)}
          onConfirm={handleConfirmBlock}
        />
      )}

      {showRemoveConfirm && (
        <RemoveConnectionConfirmDialog
          contactName={displayName}
          removing={removeActionBusy}
          onCancel={() => setShowRemoveConfirm(false)}
          onConfirm={handleConfirmRemoveConnection}
        />
      )}

      {showMuteModal && onMuteChat && (
        <MuteChatModal
          open={showMuteModal}
          contactName={displayName}
          onClose={() => setShowMuteModal(false)}
          onConfirmMute={async (duration) => {
            await onMuteChat(duration);
          }}
        />
      )}
    </div>
  );
};
