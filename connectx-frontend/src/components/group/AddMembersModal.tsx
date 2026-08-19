import React, { useState } from 'react';
import { UserPlus, X, Loader2, Check, XCircle, Clock } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { groupKeyManager } from '../../crypto/groupKeyManager';
import { Group, User } from '../../types';
import { GroupMemberPicker } from './GroupMemberPicker';
import { UserAvatar } from '../common/UserAvatar';
import { addMemberOutcomeMessage } from '../../utils/groupLabels';
import { groupErrorMessage } from '../../utils/groupErrorMessages';

interface AddMembersModalProps {
  group: Group;
  currentUserId: number;
  existingMemberUserIds: Set<number>;
  onClose: () => void;
  onDone: () => void;
}

type ResultState = { user: User; status: 'pending' | 'ok' | 'error'; message: string };

export const AddMembersModal: React.FC<AddMembersModalProps> = ({
  group,
  currentUserId,
  existingMemberUserIds,
  onClose,
  onDone,
}) => {
  const [picked, setPicked] = useState<User[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [results, setResults] = useState<ResultState[] | null>(null);

  const remainingSlots = Math.max(0, 50 - group.activeMemberCount);
  const excludeIds = new Set<number>([currentUserId, ...existingMemberUserIds]);

  const handleAdd = async () => {
    if (picked.length === 0 || submitting) return;
    setSubmitting(true);
    const initial: ResultState[] = picked.map((user) => ({ user, status: 'pending', message: '' }));
    setResults(initial);

    let anyDirectAdd = false;
    for (const user of picked) {
      try {
        const result = await groupApi.createInvitation(group.id, user.id);
        if (result.outcome === 'DIRECT_ADDED') {
          anyDirectAdd = true;
        }
        setResults((prev) =>
          prev!.map((r) => (r.user.id === user.id ? { ...r, status: 'ok', message: addMemberOutcomeMessage(result.outcome) } : r))
        );
      } catch (err) {
        setResults((prev) =>
          prev!.map((r) => (r.user.id === user.id ? { ...r, status: 'error', message: groupErrorMessage(err, "Can't add this person.") } : r))
        );
      }
    }
    setSubmitting(false);
    if (anyDirectAdd) {
      // Deterministic rotation trigger: a DIRECT_ADDED member (already connected, no accept step)
      // still rotates the group's key server-side immediately -- the inviter (this actor) is
      // already active and present, so THIS client mints and distributes the new key rather than
      // leaving it to whichever other open client's passive check happens to notice first.
      const freshGroup = await groupApi.getGroup(group.id).catch(() => null);
      if (freshGroup) {
        groupKeyManager.ensureGroupKey(freshGroup, currentUserId).catch(() => {});
      }
    }
    onDone();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-500">
              <UserPlus className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Add Members</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">{group.name}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
            aria-label="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {results ? (
          <div className="space-y-2">
            {results.map((r) => (
              <div
                key={r.user.id}
                className="flex items-center gap-3 p-3 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl"
              >
                <UserAvatar user={r.user} size="sm" viewable={false} />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium truncate">{r.user.displayName || r.user.username}</p>
                  {r.status !== 'pending' && (
                    <p className={`text-xs ${r.status === 'ok' ? 'text-emerald-500' : 'text-rose-500'}`}>{r.message}</p>
                  )}
                </div>
                {r.status === 'pending' && <Loader2 className="w-4 h-4 animate-spin text-slate-400 flex-shrink-0" />}
                {r.status === 'ok' && <Check className="w-4 h-4 text-emerald-500 flex-shrink-0" />}
                {r.status === 'error' && <XCircle className="w-4 h-4 text-rose-500 flex-shrink-0" />}
              </div>
            ))}
            <button
              onClick={onClose}
              className="w-full mt-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 transition-colors"
            >
              Done
            </button>
          </div>
        ) : (
          <>
            {remainingSlots === 0 ? (
              <p className="text-sm text-slate-500 dark:text-slate-400 text-center py-4">This group is full.</p>
            ) : (
              <>
                <GroupMemberPicker selected={picked} onChange={setPicked} excludeUserIds={excludeIds} maxSelectable={remainingSlots} />
                <button
                  onClick={handleAdd}
                  disabled={picked.length === 0 || submitting}
                  className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors disabled:opacity-50"
                >
                  {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Clock className="w-4 h-4" />}
                  Add {picked.length > 0 ? `${picked.length} ` : ''}
                  {picked.length === 1 ? 'person' : 'people'}
                </button>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
};
