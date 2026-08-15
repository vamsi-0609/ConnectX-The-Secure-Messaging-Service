import React, { useState } from 'react';
import { Search, X, UserPlus, ShieldCheck, Loader2 } from 'lucide-react';
import { userApi } from '../../api/userApi';
import { conversationApi } from '../../api/conversationApi';
import { ApiRequestError } from '../../api/apiClient';
import { User, Conversation } from '../../types';
import { UserAvatar } from '../common/UserAvatar';

interface UserSearchModalProps {
  onClose: () => void;
  onSelectConversation: (conversation: Conversation) => void;
}

export const UserSearchModal: React.FC<UserSearchModalProps> = ({ onClose, onSelectConversation }) => {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<User[]>([]);
  const [searched, setSearched] = useState(false);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;

    setLoading(true);
    setSearched(true);
    try {
      const list = await userApi.searchUsers(query.trim());
      setResults(list);
    } catch (err) {
      console.error('Failed to search users:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleStartConversation = async (user: User) => {
    try {
      const conv = await conversationApi.createDirectConversation(user.id);
      onSelectConversation(conv);
      onClose();
    } catch (err) {
      if (err instanceof ApiRequestError && err.code === 'CONVERSATION_DELETED_FOR_USER') {
        alert(err.message);
        return;
      }
      const message = err instanceof Error ? err.message : 'Failed to start conversation';
      alert('Failed to start conversation: ' + message);
    }
  };

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
        <div className="max-h-64 overflow-y-auto space-y-2 pt-1">
          {loading ? (
            <div className="text-center py-8 text-xs text-slate-400">Searching ConnectX database...</div>
          ) : searched && results.length === 0 ? (
            <div className="text-center py-8 text-xs text-slate-400">No users found matching "{query}".</div>
          ) : (
            results.map((user) => (
              <div
                key={user.id}
                className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 hover:bg-slate-100 dark:hover:bg-slate-800 border border-slate-200 dark:border-slate-700/60 rounded-2xl transition-all"
              >
                <div className="flex items-center gap-3">
                  <UserAvatar user={user} size="sm" />

                  <div>
                    <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-1.5">
                      {user.displayName || user.username}
                      <ShieldCheck className="w-3.5 h-3.5 text-pink-500" />
                    </h4>
                    <p className="text-xs text-slate-500 dark:text-slate-400 font-mono">@{user.username}</p>
                  </div>
                </div>

                <button
                  onClick={() => handleStartConversation(user)}
                  className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5"
                >
                  <UserPlus className="w-3.5 h-3.5" />
                  <span>Start Chat</span>
                </button>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
