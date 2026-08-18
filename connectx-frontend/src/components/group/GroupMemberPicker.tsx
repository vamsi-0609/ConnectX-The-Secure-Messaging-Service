import React, { useState } from 'react';
import { Search, X, Loader2, Check } from 'lucide-react';
import { userApi } from '../../api/userApi';
import { User } from '../../types';
import { UserAvatar } from '../common/UserAvatar';

interface GroupMemberPickerProps {
  selected: User[];
  onChange: (users: User[]) => void;
  // User ids that can never be picked (e.g. the current user, or existing group members).
  excludeUserIds: Set<number>;
  maxSelectable?: number;
}

// Shared search-and-multi-select used by both CreateGroupModal (initial members) and
// AddMembersModal (adding to an existing group) -- one picker, not two near-duplicates. Search is
// explicit-submit (mirrors UserSearchModal's convention), not debounced-as-you-type.
export const GroupMemberPicker: React.FC<GroupMemberPickerProps> = ({
  selected,
  onChange,
  excludeUserIds,
  maxSelectable,
}) => {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<User[]>([]);
  const [searched, setSearched] = useState(false);
  const searchSeqRef = React.useRef(0);

  const selectedIds = new Set(selected.map((u) => u.id));
  const atLimit = maxSelectable != null && selected.length >= maxSelectable;

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;
    const seq = ++searchSeqRef.current;
    setLoading(true);
    setSearched(true);
    try {
      const list = await userApi.searchUsers(query.trim());
      if (seq === searchSeqRef.current) {
        setResults(list);
      }
    } catch {
      if (seq === searchSeqRef.current) {
        setResults([]);
      }
    } finally {
      if (seq === searchSeqRef.current) {
        setLoading(false);
      }
    }
  };

  const toggleUser = (user: User) => {
    if (selectedIds.has(user.id)) {
      onChange(selected.filter((u) => u.id !== user.id));
    } else if (!atLimit) {
      onChange([...selected, user]);
    }
  };

  return (
    <div className="space-y-2.5">
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selected.map((user) => (
            <span
              key={user.id}
              className="inline-flex items-center gap-1.5 pl-1 pr-2 py-1 bg-indigo-50 dark:bg-indigo-500/10 border border-indigo-200 dark:border-indigo-500/30 rounded-full text-xs font-medium text-indigo-700 dark:text-indigo-300"
            >
              <UserAvatar user={user} size="xs" className="w-5 h-5 text-[10px]" viewable={false} />
              {user.displayName || user.username}
              <button
                type="button"
                onClick={() => toggleUser(user)}
                className="text-indigo-400 hover:text-indigo-700 dark:hover:text-indigo-200"
                aria-label={`Remove ${user.displayName || user.username}`}
              >
                <X className="w-3 h-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      <form onSubmit={handleSearch} className="relative">
        <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by username..."
          className="w-full pl-9 pr-3 py-2.5 bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500/50 transition-colors"
        />
      </form>

      {atLimit && <p className="text-[11px] text-amber-600 dark:text-amber-400">You've reached the member limit for this action.</p>}

      <div className="max-h-52 overflow-y-auto space-y-1">
        {loading ? (
          <div className="flex items-center justify-center py-6 text-slate-400">
            <Loader2 className="w-4 h-4 animate-spin" />
          </div>
        ) : searched && results.length === 0 ? (
          <p className="text-xs text-slate-400 text-center py-4">No one found.</p>
        ) : (
          results
            .filter((u) => !excludeUserIds.has(u.id))
            .map((user) => {
              const isSelected = selectedIds.has(user.id);
              return (
                <button
                  key={user.id}
                  type="button"
                  onClick={() => toggleUser(user)}
                  disabled={!isSelected && atLimit}
                  className="w-full flex items-center gap-2.5 p-2 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors text-left disabled:opacity-40"
                >
                  <UserAvatar user={user} size="sm" viewable={false} />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                      {user.displayName || user.username}
                    </p>
                    <p className="text-[11px] text-slate-400 truncate">@{user.username}</p>
                  </div>
                  {isSelected && (
                    <span className="w-5 h-5 rounded-full bg-indigo-600 flex items-center justify-center flex-shrink-0">
                      <Check className="w-3 h-3 text-white" />
                    </span>
                  )}
                </button>
              );
            })
        )}
      </div>
    </div>
  );
};
