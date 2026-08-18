import React, { useEffect, useState } from 'react';
import {
  ShieldCheck,
  X,
  ArrowLeft,
  ShieldOff,
  UserMinus,
  UserPlus,
  Check,
  XCircle,
  Clock,
  Loader2,
  Lock,
} from 'lucide-react';
import { User, ConnectionRequestDto, RelationshipStatus } from '../../types';
import { UserAvatar } from '../common/UserAvatar';
import { BlockUserConfirmDialog } from './BlockUserConfirmDialog';
import { RemoveConnectionConfirmDialog } from './RemoveConnectionConfirmDialog';

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
}) => {
  const [showBlockConfirm, setShowBlockConfirm] = useState(false);
  const [blockActionBusy, setBlockActionBusy] = useState(false);
  const [showRemoveConfirm, setShowRemoveConfirm] = useState(false);
  const [removeActionBusy, setRemoveActionBusy] = useState(false);
  const [connectionActionBusy, setConnectionActionBusy] = useState(false);

  if (!recipient) return null;

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
    <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-80 h-full bg-white dark:bg-slate-900 border-l border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0 transition-colors duration-300 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none">
      {/* Header */}
      <div className="h-16 px-4 border-b border-slate-200 dark:border-slate-800/80 flex items-center justify-between flex-shrink-0">
        <div className="flex items-center gap-2">
          <button
            onClick={onClose}
            className="md:hidden p-1.5 -ml-1.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            aria-label="Back"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <h3 className="font-bold text-base">Contact Info</h3>
        </div>
        <button
          onClick={onClose}
          className="hidden md:block p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          aria-label="Close"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Main Profile Info */}
      <div className="p-6 text-center border-b border-slate-200 dark:border-slate-800/80 space-y-3 flex-shrink-0 bg-slate-50/40 dark:bg-slate-900/40">
        <UserAvatar user={recipient} size="xl" className="mx-auto shadow-xl" />
        <div>
          <h2 className="text-lg font-bold tracking-tight">{recipient.displayName || recipient.username}</h2>
          <p className="text-xs text-violet-600 dark:text-violet-400 font-medium">@{recipient.username}</p>
          {recipient.email && (
            <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-0.5">{recipient.email}</p>
          )}
        </div>
      </div>

      {/* Privacy & Security */}
      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1.5">
        <div className="flex items-center gap-2 text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-wider">
          <ShieldCheck className="w-4 h-4" />
          <span>Privacy &amp; Security</span>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          Messages and media in this chat are end-to-end encrypted. Nobody outside of this conversation can read them.
        </p>
      </div>

      {/* Relationship Actions */}
      {(onBlock || onUnblock || relationship !== undefined) && (
        <div className="p-4 space-y-4 flex-1">
          {relationship === 'CONNECTED' && onRemoveConnection && (
            <div className="space-y-2">
              <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                Connection Status
              </div>
              <div className="p-3 rounded-2xl bg-slate-50 dark:bg-slate-800/40 border border-slate-200/80 dark:border-slate-800/80 flex items-center justify-between">
                <span className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 flex items-center gap-1.5">
                  <Check className="w-3.5 h-3.5" />
                  Connected
                </span>
                <button
                  onClick={() => setShowRemoveConfirm(true)}
                  disabled={removeActionBusy}
                  className="px-3 py-1.5 rounded-xl text-xs font-semibold text-rose-500 hover:bg-rose-500/10 transition-colors disabled:opacity-50 flex items-center gap-1"
                >
                  {removeActionBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserMinus className="w-3.5 h-3.5" />}
                  Remove
                </button>
              </div>
            </div>
          )}

          {(relationship === 'NOT_CONNECTED' || relationship === 'LEGACY_CHAT') && onSendRequest && (
            <div className="space-y-2">
              <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                Connection Status
              </div>
              <button
                onClick={handleAddConnection}
                disabled={connectionActionBusy}
                className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-2xl text-xs font-semibold bg-violet-600 hover:bg-violet-500 text-white transition-colors disabled:opacity-50 shadow-sm"
              >
                {connectionActionBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserPlus className="w-3.5 h-3.5" />}
                Add Connection
              </button>
            </div>
          )}

          {relationship === 'REQUEST_SENT' && sentRequest && onCancelRequest && (
            <div className="space-y-2">
              <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                Connection Status
              </div>
              <div className="p-3 rounded-2xl bg-slate-50 dark:bg-slate-800/40 border border-slate-200/80 dark:border-slate-800/80 space-y-2">
                <div className="flex items-center gap-2 text-xs font-medium text-amber-500">
                  <Clock className="w-3.5 h-3.5" />
                  <span>Request Pending</span>
                </div>
                <button
                  onClick={handleWithdrawRequest}
                  disabled={connectionActionBusy}
                  className="w-full flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
                >
                  {connectionActionBusy && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                  Withdraw Request
                </button>
              </div>
            </div>
          )}

          {relationship === 'REQUEST_RECEIVED' && receivedRequest && onAcceptRequest && onRejectRequest && (
            <div className="space-y-2">
              <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                Incoming Request
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={handleAcceptRequest}
                  disabled={connectionActionBusy}
                  className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white transition-colors disabled:opacity-50 shadow-sm"
                >
                  {connectionActionBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                  Accept
                </button>
                <button
                  onClick={handleRejectRequest}
                  disabled={connectionActionBusy}
                  className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
                >
                  {connectionActionBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                  Reject
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Danger Zone */}
      {(onBlock || onUnblock) && (
        <div className="p-4 border-t border-slate-200 dark:border-slate-800/80 space-y-2 flex-shrink-0">
          <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
            Safety
          </div>
          {isBlocked ? (
            <button
              onClick={handleUnblock}
              disabled={blockActionBusy}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-2xl text-xs font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
            >
              {blockActionBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldOff className="w-4 h-4" />}
              Unblock {recipient.displayName || recipient.username}
            </button>
          ) : (
            <button
              onClick={() => setShowBlockConfirm(true)}
              disabled={blockActionBusy}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-2xl text-xs font-semibold text-rose-600 dark:text-rose-400 border border-rose-200/80 dark:border-rose-900/50 hover:bg-rose-50 dark:hover:bg-rose-950/30 transition-colors disabled:opacity-50"
            >
              <ShieldOff className="w-4 h-4" />
              Block {recipient.displayName || recipient.username}
            </button>
          )}
        </div>
      )}

      {showBlockConfirm && (
        <BlockUserConfirmDialog
          contactName={recipient.displayName || recipient.username}
          blocking={blockActionBusy}
          onCancel={() => setShowBlockConfirm(false)}
          onConfirm={handleConfirmBlock}
        />
      )}

      {showRemoveConfirm && (
        <RemoveConnectionConfirmDialog
          contactName={recipient.displayName || recipient.username}
          removing={removeActionBusy}
          onCancel={() => setShowRemoveConfirm(false)}
          onConfirm={handleConfirmRemoveConnection}
        />
      )}
    </div>
  );
};

