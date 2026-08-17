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
    <footer className="chat-composer flex-shrink-0 px-4 py-3.5 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800/80 select-none">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="p-2 rounded-xl bg-slate-100 dark:bg-slate-800 text-slate-400 dark:text-slate-500 flex-shrink-0">
            <Lock className="w-4 h-4" />
          </div>
          <div className="min-w-0">
            {relationship === 'REQUEST_SENT' && (
              <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 truncate">
                Connection request pending
              </p>
            )}
            {relationship === 'REQUEST_RECEIVED' && (
              <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 truncate">
                {recipientName} sent you a connection request
              </p>
            )}
            {relationship === 'BLOCKED_BY_ME' && (
              <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 truncate">
                You&apos;ve blocked {recipientName}
              </p>
            )}
            {(relationship === 'NOT_CONNECTED' || relationship === 'LEGACY_CHAT') && (
              <>
                <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 truncate">
                  You&apos;re no longer connected with {recipientName}
                </p>
                <p className="text-[11px] text-slate-400 dark:text-slate-500 truncate">
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
              className="px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserPlus className="w-3.5 h-3.5" />}
              Add Connection
            </button>
          )}

          {relationship === 'REQUEST_SENT' && sentRequest && onCancelRequest && (
            <button
              onClick={() => runAction(() => onCancelRequest(sentRequest.id, recipientId))}
              disabled={busy}
              className="px-3.5 py-2 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Clock className="w-3.5 h-3.5" />}
              Withdraw Request
            </button>
          )}

          {relationship === 'REQUEST_RECEIVED' && receivedRequest && onAcceptRequest && onRejectRequest && (
            <>
              <button
                onClick={() => runAction(() => onAcceptRequest(receivedRequest.id, recipientId))}
                disabled={busy}
                className="px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
              >
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                Accept
              </button>
              <button
                onClick={() => runAction(() => onRejectRequest(receivedRequest.id, recipientId))}
                disabled={busy}
                className="px-3.5 py-2 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50"
              >
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                Reject
              </button>
            </>
          )}

          {relationship === 'BLOCKED_BY_ME' && onUnblock && (
            <button
              onClick={() => runAction(() => onUnblock(recipientId))}
              disabled={busy}
              className="px-3.5 py-2 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldOff className="w-3.5 h-3.5" />}
              Unblock
            </button>
          )}
        </div>
      </div>
    </footer>
  );
};
