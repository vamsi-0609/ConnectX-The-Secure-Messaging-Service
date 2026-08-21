import React, { useState } from 'react';
import { Lock, UserPlus, Clock, Check, XCircle, ShieldOff, Loader2 } from 'lucide-react';
import { ConnectionRequestDto, RelationshipStatus } from '../../types';

// Replaces the composer (MessageInput) whenever the current relationship with the conversation's
// recipient doesn't permit sending a new DIRECT message -- NOT_CONNECTED, LEGACY_CHAT (history
// with no current connection), a pending request in either direction, or a block. Existing message
// history stays visible above this (MessageFeed is untouched); only new-message creation is gated.
interface ChatRelationshipGateProps {
  recipientId: number;
  recipientName: string;
  relationship: RelationshipStatus;
  sentRequest?: ConnectionRequestDto;
  receivedRequest?: ConnectionRequestDto;
  onSendRequest?: (userId: number) => Promise<void>;
  onCancelRequest?: (requestId: number, userId: number) => Promise<void>;
  onAcceptRequest?: (requestId: number, userId: number) => Promise<void>;
  onRejectRequest?: (requestId: number, userId: number) => Promise<void>;
  onUnblock?: (userId: number) => Promise<void>;
}

export const ChatRelationshipGate: React.FC<ChatRelationshipGateProps> = ({
  recipientId,
  recipientName,
  relationship,
  sentRequest,
  receivedRequest,
  onSendRequest,
  onCancelRequest,
  onAcceptRequest,
  onRejectRequest,
  onUnblock,
}) => {
  const [busy, setBusy] = useState(false);

  const runAction = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    try {
      await action();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Action failed';
      alert(message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <footer className="chat-composer flex-shrink-0 px-3 sm:px-4 md:px-6 py-2.5 sm:py-3 bg-white/95 dark:bg-[#0a0e1a]/95 backdrop-blur-sm border-t border-slate-200/90 dark:border-slate-800/80 select-none z-20">
      <div className="max-w-4xl mx-auto">
        <div className="p-2.5 sm:p-3 rounded-2xl bg-slate-50/90 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/70 shadow-sm flex items-center justify-between gap-3 min-w-0">
          <div className="flex items-center gap-2.5 min-w-0 flex-1">
            <div className="w-8 h-8 sm:w-9 sm:h-9 rounded-xl bg-slate-200/70 dark:bg-slate-800/80 text-slate-500 dark:text-slate-400 flex items-center justify-center flex-shrink-0">
              <Lock className="w-4 h-4 text-violet-500 dark:text-violet-400" />
            </div>
            <div className="min-w-0 flex-1">
              {relationship === 'REQUEST_SENT' && (
                <p className="text-xs font-semibold text-slate-700 dark:text-slate-200 truncate">
                  Connection request pending
                </p>
              )}
              {relationship === 'REQUEST_RECEIVED' && (
                <p className="text-xs font-semibold text-slate-700 dark:text-slate-200 truncate">
                  {recipientName} sent you a connection request
                </p>
              )}
              {relationship === 'BLOCKED_BY_ME' && (
                <p className="text-xs font-semibold text-slate-700 dark:text-slate-200 truncate">
                  You&apos;ve blocked {recipientName}
                </p>
              )}
              {(relationship === 'NOT_CONNECTED' || relationship === 'LEGACY_CHAT') && (
                <>
                  <p className="text-xs font-semibold text-slate-700 dark:text-slate-200 truncate">
                    You&apos;re no longer connected with {recipientName}
                  </p>
                  <p className="text-[11px] text-slate-500 dark:text-slate-400 truncate mt-0.5">
                    Existing messages are preserved securely.
                  </p>
                </>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2 flex-shrink-0">
            {(relationship === 'NOT_CONNECTED' || relationship === 'LEGACY_CHAT') && onSendRequest && (
              <button
                onClick={() => runAction(() => onSendRequest(recipientId))}
                disabled={busy}
                className="px-3.5 py-2 bg-violet-600 hover:bg-violet-500 active:scale-95 text-white text-xs font-semibold rounded-xl shadow-sm hover:shadow-md hover:shadow-violet-600/20 transition-all flex items-center gap-1.5 disabled:opacity-50 cursor-pointer"
              >
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserPlus className="w-3.5 h-3.5" />}
                <span>Add Connection</span>
              </button>
            )}

            {relationship === 'REQUEST_SENT' && sentRequest && onCancelRequest && (
              <button
                onClick={() => runAction(() => onCancelRequest(sentRequest.id, recipientId))}
                disabled={busy}
                className="px-3.5 py-2 bg-slate-200 dark:bg-slate-800 hover:bg-slate-300 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50 cursor-pointer"
              >
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Clock className="w-3.5 h-3.5" />}
                <span>Withdraw Request</span>
              </button>
            )}

            {relationship === 'REQUEST_RECEIVED' && receivedRequest && onAcceptRequest && onRejectRequest && (
              <>
                <button
                  onClick={() => runAction(() => onAcceptRequest(receivedRequest.id, recipientId))}
                  disabled={busy}
                  className="px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 active:scale-95 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50 cursor-pointer"
                >
                  {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                  <span>Accept</span>
                </button>
                <button
                  onClick={() => runAction(() => onRejectRequest(receivedRequest.id, recipientId))}
                  disabled={busy}
                  className="px-3.5 py-2 bg-slate-200 dark:bg-slate-800 hover:bg-slate-300 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50 cursor-pointer"
                >
                  {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                  <span>Reject</span>
                </button>
              </>
            )}

            {relationship === 'BLOCKED_BY_ME' && onUnblock && (
              <button
                onClick={() => runAction(() => onUnblock(recipientId))}
                disabled={busy}
                className="px-3.5 py-2 bg-slate-200 dark:bg-slate-800 hover:bg-slate-300 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50 cursor-pointer"
              >
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldOff className="w-3.5 h-3.5" />}
                <span>Unblock</span>
              </button>
            )}
          </div>
        </div>
      </div>
    </footer>
  );
};
