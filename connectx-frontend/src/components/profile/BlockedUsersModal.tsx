import React, { useEffect, useState } from 'react';
import { ShieldOff, X, Loader2 } from 'lucide-react';
import { blockApi } from '../../api/blockApi';
import { UserBlockDto } from '../../types';
import { UserAvatar } from '../common/UserAvatar';

interface BlockedUsersModalProps {
  onClose: () => void;
  // Reuses the existing App.tsx handler (same one ContactInfoDrawer/UserSearchModal/
  // ChatRelationshipGate already call) so blockedUserIds -- the single shared source of truth
  // for relationship derivation -- stays in sync. This modal never mutates that state itself.
  onUnblock: (userId: number) => Promise<void>;
}

export const BlockedUsersModal: React.FC<BlockedUsersModalProps> = ({ onClose, onUnblock }) => {
  const [blocks, setBlocks] = useState<UserBlockDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyIds, setBusyIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    blockApi
      .getBlocks()
      .then((list) => {
        if (!cancelled) setBlocks(list);
      })
      .catch((err) => {
        console.error('Failed to load blocked users:', err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleUnblock = async (block: UserBlockDto) => {
    if (busyIds.has(block.blockedUserId)) return;
    setBusyIds((prev) => new Set(prev).add(block.blockedUserId));
    try {
      await onUnblock(block.blockedUserId);
      setBlocks((prev) => prev.filter((b) => b.blockedUserId !== block.blockedUserId));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to unblock user';
      alert(message);
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(block.blockedUserId);
        return next;
      });
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-red-500/10 text-red-500">
              <ShieldOff className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Blocked Users</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">People you've blocked on ConnectX</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="max-h-80 overflow-y-auto space-y-2">
          {loading ? (
            <div className="text-center py-8 text-xs text-slate-400">Loading blocked users...</div>
          ) : blocks.length === 0 ? (
            <div className="text-center py-8 text-xs text-slate-400">No blocked users</div>
          ) : (
            blocks.map((block) => {
              const busy = busyIds.has(block.blockedUserId);
              return (
                <div
                  key={block.id}
                  className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl gap-2"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <UserAvatar
                      user={{
                        username: block.blockedUsername,
                        displayName: block.blockedDisplayName,
                        profileImageUrl: block.blockedProfileImageUrl,
                      }}
                      size="sm"
                    />
                    <div className="min-w-0">
                      <h4 className="text-sm font-bold truncate">
                        {block.blockedDisplayName || block.blockedUsername}
                      </h4>
                      <p className="text-xs text-slate-500 dark:text-slate-400 font-mono truncate">
                        @{block.blockedUsername}
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={() => handleUnblock(block)}
                    disabled={busy}
                    className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all flex items-center gap-1.5 disabled:opacity-50 flex-shrink-0"
                  >
                    {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
                    Unblock
                  </button>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};
