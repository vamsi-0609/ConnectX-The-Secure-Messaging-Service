import React, { useState } from 'react';
import { UserPlus, X, Check, XCircle, Loader2, Clock } from 'lucide-react';
import { ConnectionRequestDto } from '../../types';
import { UserAvatar } from '../common/UserAvatar';

interface ConnectionRequestsModalProps {
  onClose: () => void;
  receivedRequests: ConnectionRequestDto[];
  sentRequests: ConnectionRequestDto[];
  onAcceptRequest: (requestId: number, userId: number) => Promise<void>;
  onRejectRequest: (requestId: number, userId: number) => Promise<void>;
  onCancelRequest: (requestId: number, userId: number) => Promise<void>;
}

export const ConnectionRequestsModal: React.FC<ConnectionRequestsModalProps> = ({
  onClose,
  receivedRequests,
  sentRequests,
  onAcceptRequest,
  onRejectRequest,
  onCancelRequest,
}) => {
  const [tab, setTab] = useState<'received' | 'sent'>('received');
  const [busyIds, setBusyIds] = useState<Set<number>>(new Set());

  const withBusy = async (requestId: number, action: () => Promise<void>) => {
    if (busyIds.has(requestId)) return;
    setBusyIds((prev) => new Set(prev).add(requestId));
    try {
      await action();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Action failed';
      alert(message);
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(requestId);
        return next;
      });
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-indigo-600/10 dark:bg-indigo-600/20 text-indigo-600 dark:text-indigo-400">
              <UserPlus className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Connection Requests</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Manage your connections</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex items-center gap-1.5 p-1 bg-slate-100 dark:bg-slate-800/60 rounded-xl">
          <button
            onClick={() => setTab('received')}
            className={`flex-1 px-3 py-2 text-xs font-semibold rounded-lg transition-all ${
              tab === 'received'
                ? 'bg-white dark:bg-slate-900 text-indigo-600 dark:text-indigo-400 shadow-sm'
                : 'text-slate-500 dark:text-slate-400'
            }`}
          >
            Received ({receivedRequests.length})
          </button>
          <button
            onClick={() => setTab('sent')}
            className={`flex-1 px-3 py-2 text-xs font-semibold rounded-lg transition-all ${
              tab === 'sent'
                ? 'bg-white dark:bg-slate-900 text-indigo-600 dark:text-indigo-400 shadow-sm'
                : 'text-slate-500 dark:text-slate-400'
            }`}
          >
            Sent ({sentRequests.length})
          </button>
        </div>

        <div className="max-h-80 overflow-y-auto space-y-2">
          {tab === 'received' &&
            (receivedRequests.length === 0 ? (
              <div className="text-center py-8 text-xs text-slate-400">No pending requests.</div>
            ) : (
              receivedRequests.map((req) => {
                const busy = busyIds.has(req.id);
                return (
                  <div
                    key={req.id}
                    className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl gap-2"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <UserAvatar
                        user={{
                          username: req.requesterUsername,
                          displayName: req.requesterDisplayName,
                          profileImageUrl: req.requesterProfileImageUrl,
                        }}
                        size="sm"
                      />
                      <div className="min-w-0">
                        <h4 className="text-sm font-bold truncate">{req.requesterDisplayName || req.requesterUsername}</h4>
                        <p className="text-xs text-slate-500 dark:text-slate-400 font-mono truncate">
                          @{req.requesterUsername}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      <button
                        onClick={() => withBusy(req.id, () => onAcceptRequest(req.id, req.requesterId))}
                        disabled={busy}
                        className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
                      >
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                        <span>Accept</span>
                      </button>
                      <button
                        onClick={() => withBusy(req.id, () => onRejectRequest(req.id, req.requesterId))}
                        disabled={busy}
                        className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all disabled:opacity-50"
                      >
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                      </button>
                    </div>
                  </div>
                );
              })
            ))}

          {tab === 'sent' &&
            (sentRequests.length === 0 ? (
              <div className="text-center py-8 text-xs text-slate-400">No sent requests.</div>
            ) : (
              sentRequests.map((req) => {
                const busy = busyIds.has(req.id);
                return (
                  <div
                    key={req.id}
                    className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl gap-2"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <UserAvatar
                        user={{
                          username: req.recipientUsername,
                          displayName: req.recipientDisplayName,
                          profileImageUrl: req.recipientProfileImageUrl,
                        }}
                        size="sm"
                      />
                      <div className="min-w-0">
                        <h4 className="text-sm font-bold truncate">{req.recipientDisplayName || req.recipientUsername}</h4>
                        <p className="text-xs text-slate-500 dark:text-slate-400 font-mono truncate">
                          @{req.recipientUsername}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      <span className="px-2 py-1.5 text-xs font-semibold text-slate-400 dark:text-slate-500 flex items-center gap-1">
                        <Clock className="w-3.5 h-3.5" />
                      </span>
                      <button
                        onClick={() => withBusy(req.id, () => onCancelRequest(req.id, req.recipientId))}
                        disabled={busy}
                        className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all disabled:opacity-50"
                      >
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Cancel'}
                      </button>
                    </div>
                  </div>
                );
              })
            ))}
        </div>
      </div>
    </div>
  );
};
