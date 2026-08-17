import React, { useState } from 'react';
import { Search, X, ShieldCheck, Loader2, UserPlus, Check, XCircle, MessageCircle, Clock, ShieldOff } from 'lucide-react';
import { userApi } from '../../api/userApi';
import { conversationApi } from '../../api/conversationApi';
import { ApiRequestError } from '../../api/apiClient';
import { User, Conversation, ConnectionRequestDto } from '../../types';
import { UserAvatar } from '../common/UserAvatar';
import { getRelationshipStatus, findExistingDirectConversation } from '../../utils/relationship';

interface UserSearchModalProps {
  onClose: () => void;
  onSelectConversation: (conversation: Conversation) => void;
  conversations: Conversation[];
  connectedUserIds: Set<number>;
  blockedUserIds: Set<number>;
  sentRequestsByUserId: Map<number, ConnectionRequestDto>;
  receivedRequestsByUserId: Map<number, ConnectionRequestDto>;
  onSendRequest: (userId: number) => Promise<void>;
  onCancelRequest: (requestId: number, userId: number) => Promise<void>;
  onAcceptRequest: (requestId: number, userId: number) => Promise<void>;
  onRejectRequest: (requestId: number, userId: number) => Promise<void>;
  onUnblockUser: (userId: number) => Promise<void>;
}

export const UserSearchModal: React.FC<UserSearchModalProps> = ({
  onClose,
  onSelectConversation,
  conversations,
  connectedUserIds,
  blockedUserIds,
  sentRequestsByUserId,
  receivedRequestsByUserId,
  onSendRequest,
  onCancelRequest,
  onAcceptRequest,
  onRejectRequest,
  onUnblockUser,
}) => {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<User[]>([]);
  const [searched, setSearched] = useState(false);
  const [actionUserIds, setActionUserIds] = useState<Set<number>>(new Set());
  const searchSeqRef = React.useRef(0);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;

    const currentSeq = ++searchSeqRef.current;
    setLoading(true);
    setSearched(true);
    try {
      const list = await userApi.searchUsers(query.trim());
      if (currentSeq === searchSeqRef.current) {
        setResults(list);
      }
    } catch (err) {
      console.error('Failed to search users:', err);
    } finally {
      if (currentSeq === searchSeqRef.current) {
        setLoading(false);
      }
    }
  };

  const withBusy = async (userId: number, action: () => Promise<void>) => {
    if (actionUserIds.has(userId)) return;
    setActionUserIds((prev) => new Set(prev).add(userId));
    try {
      await action();
    } catch (err) {
      if (err instanceof ApiRequestError && err.code === 'CONVERSATION_DELETED_FOR_USER') {
        alert(err.message);
      } else {
        const message = err instanceof Error ? err.message : 'Action failed';
        alert(message);
      }
    } finally {
      setActionUserIds((prev) => {
        const next = new Set(prev);
        next.delete(userId);
        return next;
      });
    }
  };

  const handleMessage = (user: User) =>
    withBusy(user.id, async () => {
      const existing = findExistingDirectConversation(user.id, conversations);
      if (existing) {
        onSelectConversation(existing);
        onClose();
        return;
      }
      const conv = await conversationApi.createDirectConversation(user.id);
      onSelectConversation(conv);
      onClose();
    });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white">
        {/* Modal Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-indigo-600/10 dark:bg-indigo-600/20 text-indigo-600 dark:text-indigo-400">
              <Search className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">New Conversation</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Search people to start an encrypted chat</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Clean Search Form */}
        <form onSubmit={handleSearch} className="flex gap-2">
          <div className="relative flex-1">
            <Search className="w-4 h-4 absolute left-3.5 top-3.5 text-slate-400 pointer-events-none" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search by username..."
              className="w-full pl-10 pr-4 py-2.5 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 transition-all select-text"
              autoFocus
            />
          </div>
          <button
            type="submit"
            disabled={loading || !query.trim()}
            className="px-4 py-2.5 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-sm font-semibold rounded-xl shadow-md transition-all flex items-center gap-2"
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <span>Search</span>}
          </button>
        </form>

        {/* Search Results Feed */}
        <div className="max-h-72 overflow-y-auto space-y-2 pt-1">
          {loading ? (
            <div className="text-center py-8 text-xs text-slate-400">Searching ConnectX database...</div>
          ) : searched && results.length === 0 ? (
            <div className="text-center py-8 text-xs text-slate-400">No users found matching "{query}".</div>
          ) : (
            results.map((user) => {
              const relationship = getRelationshipStatus(user.id, {
                conversations,
                connectedUserIds,
                blockedUserIds,
                sentRequestsByUserId,
                receivedRequestsByUserId,
              });
              const busy = actionUserIds.has(user.id);

              return (
                <div
                  key={user.id}
                  className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 hover:bg-slate-100 dark:hover:bg-slate-800 border border-slate-200 dark:border-slate-700/60 rounded-2xl transition-all gap-2"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <UserAvatar user={user} size="sm" />

                    <div className="min-w-0">
                      <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-1.5">
                        <span className="truncate">{user.displayName || user.username}</span>
                        <ShieldCheck className="w-3.5 h-3.5 text-pink-500 flex-shrink-0" />
                      </h4>
                      <p className="text-xs text-slate-500 dark:text-slate-400 font-mono truncate">@{user.username}</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-1.5 flex-shrink-0">
                    {relationship === 'BLOCKED_BY_ME' && (
                      <>
                        <span className="px-2.5 py-1.5 text-xs font-semibold text-slate-400 dark:text-slate-500 flex items-center gap-1">
                          <ShieldOff className="w-3.5 h-3.5" />
                          Blocked
                        </span>
                        <button
                          onClick={() => withBusy(user.id, () => onUnblockUser(user.id))}
                          disabled={busy}
                          className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all disabled:opacity-50"
                        >
                          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Unblock'}
                        </button>
                      </>
                    )}

                    {relationship === 'CONNECTED' && (
                      <button
                        onClick={() => handleMessage(user)}
                        disabled={busy}
                        className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
                      >
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <MessageCircle className="w-3.5 h-3.5" />}
                        <span>Message</span>
                      </button>
                    )}

                    {/* LEGACY_CHAT (past conversation, no current connection) is intentionally
                        treated the same as NOT_CONNECTED here -- chat history must not grant a
                        currently-valid "Message" action; see getRelationshipStatus's priority
                        comment in relationship.ts. */}
                    {(relationship === 'NOT_CONNECTED' || relationship === 'LEGACY_CHAT') && (
                      <button
                        onClick={() => withBusy(user.id, () => onSendRequest(user.id))}
                        disabled={busy}
                        className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
                      >
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <UserPlus className="w-3.5 h-3.5" />}
                        <span>Send Request</span>
                      </button>
                    )}

                    {relationship === 'REQUEST_SENT' && (
                      <>
                        <span className="px-2.5 py-1.5 text-xs font-semibold text-slate-400 dark:text-slate-500 flex items-center gap-1">
                          <Clock className="w-3.5 h-3.5" />
                          Request Sent
                        </span>
                        <button
                          onClick={() => {
                            const req = sentRequestsByUserId.get(user.id);
                            if (req) withBusy(user.id, () => onCancelRequest(req.id, user.id));
                          }}
                          disabled={busy}
                          className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all disabled:opacity-50"
                        >
                          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Cancel'}
                        </button>
                      </>
                    )}

                    {relationship === 'REQUEST_RECEIVED' && (
                      <>
                        <button
                          onClick={() => {
                            const req = receivedRequestsByUserId.get(user.id);
                            if (req) withBusy(user.id, () => onAcceptRequest(req.id, user.id));
                          }}
                          disabled={busy}
                          className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
                        >
                          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                          <span>Accept</span>
                        </button>
                        <button
                          onClick={() => {
                            const req = receivedRequestsByUserId.get(user.id);
                            if (req) withBusy(user.id, () => onRejectRequest(req.id, user.id));
                          }}
                          disabled={busy}
                          className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1 disabled:opacity-50"
                        >
                          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                        </button>
                      </>
                    )}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};
