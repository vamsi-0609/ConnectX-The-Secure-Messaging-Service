import React, { useState } from 'react';
import { Mail, X, Loader2, Clock } from 'lucide-react';
import { GroupInvitation } from '../../types';
import { GroupAvatar } from './GroupAvatar';
import { GroupInvitationCard } from './GroupInvitationCard';
import { groupErrorMessage } from '../../utils/groupErrorMessages';

interface GroupInvitationsModalProps {
  onClose: () => void;
  receivedInvitations: GroupInvitation[];
  sentInvitations: GroupInvitation[];
  onAccept: (invitationId: number) => Promise<void>;
  onReject: (invitationId: number) => Promise<void>;
  onCancel: (invitationId: number) => Promise<void>;
  // When set (opened from within a specific group's Settings screen), both tabs are scoped to
  // that group only. Omitted when opened from the sidebar's global entry point.
  groupIdFilter?: number;
}

export const GroupInvitationsModal: React.FC<GroupInvitationsModalProps> = ({
  onClose,
  receivedInvitations,
  sentInvitations,
  onAccept,
  onReject,
  onCancel,
  groupIdFilter,
}) => {
  const [tab, setTab] = useState<'received' | 'sent'>('received');
  const [busyIds, setBusyIds] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const received = groupIdFilter ? receivedInvitations.filter((i) => i.groupId === groupIdFilter) : receivedInvitations;
  const sent = groupIdFilter ? sentInvitations.filter((i) => i.groupId === groupIdFilter) : sentInvitations;

  const withBusy = async (invitationId: number, action: () => Promise<void>) => {
    if (busyIds.has(invitationId)) return;
    setBusyIds((prev) => new Set(prev).add(invitationId));
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't complete that action."));
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(invitationId);
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
              <Mail className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Group Invitations</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Manage your group invitations</p>
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
              tab === 'received' ? 'bg-white dark:bg-slate-900 text-indigo-600 dark:text-indigo-400 shadow-sm' : 'text-slate-500 dark:text-slate-400'
            }`}
          >
            Received ({received.length})
          </button>
          <button
            onClick={() => setTab('sent')}
            className={`flex-1 px-3 py-2 text-xs font-semibold rounded-lg transition-all ${
              tab === 'sent' ? 'bg-white dark:bg-slate-900 text-indigo-600 dark:text-indigo-400 shadow-sm' : 'text-slate-500 dark:text-slate-400'
            }`}
          >
            Sent ({sent.length})
          </button>
        </div>

        {error && <p className="text-xs text-rose-500 font-medium">{error}</p>}

        <div className="max-h-80 overflow-y-auto space-y-2">
          {tab === 'received' &&
            (received.length === 0 ? (
              <div className="text-center py-8 text-xs text-slate-400">No pending invitations.</div>
            ) : (
              received.map((inv) => (
                <GroupInvitationCard
                  key={inv.id}
                  invitation={inv}
                  busy={busyIds.has(inv.id)}
                  onAccept={() => withBusy(inv.id, () => onAccept(inv.id))}
                  onDecline={() => withBusy(inv.id, () => onReject(inv.id))}
                />
              ))
            ))}

          {tab === 'sent' &&
            (sent.length === 0 ? (
              <div className="text-center py-8 text-xs text-slate-400">No sent invitations.</div>
            ) : (
              sent.map((inv) => {
                const busy = busyIds.has(inv.id);
                return (
                  <div
                    key={inv.id}
                    className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl gap-2"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <GroupAvatar name={inv.groupName} size="sm" />
                      <div className="min-w-0">
                        <h4 className="text-sm font-bold truncate">{inv.invitee.displayName || inv.invitee.username}</h4>
                        <p className="text-xs text-slate-500 dark:text-slate-400 truncate">
                          Invitation pending &middot; {inv.groupName}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      <span className="px-2 py-1.5 text-xs font-semibold text-slate-400 dark:text-slate-500 flex items-center gap-1">
                        <Clock className="w-3.5 h-3.5" />
                      </span>
                      <button
                        onClick={() => withBusy(inv.id, () => onCancel(inv.id))}
                        disabled={busy}
                        className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all disabled:opacity-50"
                      >
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Withdraw'}
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
