import React, { useEffect, useState } from 'react';
import { ArrowLeft, Search, MoreVertical, UserPlus, Loader2 } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { ConversationMember, Group, GroupRole } from '../../types';
import { UserAvatar } from '../common/UserAvatar';
import { ROLE_LABEL } from '../../utils/groupLabels';
import { groupErrorMessage } from '../../utils/groupErrorMessages';
import { GroupMemberActionsMenu } from './GroupMemberActionsMenu';
import { RemoveMemberConfirmDialog } from './RemoveMemberConfirmDialog';
import { TransferOwnershipConfirmDialog } from './TransferOwnershipConfirmDialog';

interface GroupMembersScreenProps {
  group: Group;
  currentUserId: number;
  onBack: () => void;
  onOpenAddMembers: () => void;
  onMembersChanged?: () => void;
}

const ROLE_ORDER: GroupRole[] = ['OWNER', 'ADMIN', 'MEMBER'];

export const GroupMembersScreen: React.FC<GroupMembersScreenProps> = ({
  group,
  currentUserId,
  onBack,
  onOpenAddMembers,
  onMembersChanged,
}) => {
  const [members, setMembers] = useState<ConversationMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [openMenuUserId, setOpenMenuUserId] = useState<number | null>(null);
  const [busyUserId, setBusyUserId] = useState<number | null>(null);
  const [removeTarget, setRemoveTarget] = useState<ConversationMember | null>(null);
  const [transferTarget, setTransferTarget] = useState<ConversationMember | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadMembers = async () => {
    setLoading(true);
    try {
      const data = await groupApi.getGroupMembers(group.id);
      setMembers(data);
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't load members."));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMembers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [group.id]);

  const viewerRole: GroupRole = group.currentUserRole ?? 'MEMBER';

  const filtered = members.filter((m) => {
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return (
      m.user.username.toLowerCase().includes(q) ||
      (m.user.displayName && m.user.displayName.toLowerCase().includes(q))
    );
  });

  const sections = ROLE_ORDER.map((role) => ({
    role,
    members: filtered.filter((m) => (m.role ?? 'MEMBER') === role),
  })).filter((s) => s.members.length > 0);

  const runAction = async (member: ConversationMember, action: () => Promise<unknown>) => {
    setBusyUserId(member.user.id);
    setError(null);
    try {
      await action();
      await loadMembers();
      onMembersChanged?.();
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't update that member."));
    } finally {
      setBusyUserId(null);
      setOpenMenuUserId(null);
    }
  };

  const handleConfirmRemove = async () => {
    if (!removeTarget) return;
    await runAction(removeTarget, () => groupApi.removeMember(group.id, removeTarget.user.id));
    setRemoveTarget(null);
  };

  const handleConfirmTransfer = async () => {
    if (!transferTarget) return;
    await runAction(transferTarget, () => groupApi.transferOwnership(group.id, transferTarget.user.id));
    setTransferTarget(null);
  };

  return (
    <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-80 h-full bg-white dark:bg-slate-900 border-l border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none">
      <div className="h-16 px-4 border-b border-slate-200 dark:border-slate-800/80 flex items-center gap-2 flex-shrink-0">
        <button
          onClick={onBack}
          className="p-1.5 -ml-1.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          aria-label="Back"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="min-w-0">
          <h3 className="font-bold text-base leading-tight">Members</h3>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            {group.activeMemberCount} {group.activeMemberCount === 1 ? 'member' : 'members'}
          </p>
        </div>
      </div>

      <div className="p-3 border-b border-slate-200 dark:border-slate-800/80 space-y-2 flex-shrink-0">
        <div className="relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search members..."
            className="w-full pl-9 pr-3 py-2 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-xl text-sm outline-none focus:border-indigo-500/50 transition-colors"
          />
        </div>
        {(viewerRole === 'OWNER' || viewerRole === 'ADMIN' || group.whoCanInvite === 'ALL_MEMBERS') && (
          <button
            onClick={onOpenAddMembers}
            className="w-full flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors"
          >
            <UserPlus className="w-4 h-4" />
            Add members
          </button>
        )}
      </div>

      {error && <p className="px-4 pt-3 text-xs text-rose-500 font-medium">{error}</p>}

      <div className="flex-1 p-3 space-y-4">
        {loading ? (
          <div className="flex items-center justify-center py-10 text-slate-400">
            <Loader2 className="w-5 h-5 animate-spin" />
          </div>
        ) : sections.length === 0 ? (
          <p className="text-center text-sm text-slate-400 py-8">No members found.</p>
        ) : (
          sections.map((section) => (
            <div key={section.role} className="space-y-1.5">
              <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
                {section.role === 'OWNER' ? 'Owner' : section.role === 'ADMIN' ? 'Admins' : 'Members'} ({section.members.length})
              </p>
              {section.members.map((member) => {
                const isSelf = member.user.id === currentUserId;
                const targetRole = member.role ?? 'MEMBER';
                const showMenuTrigger = !isSelf && targetRole !== 'OWNER' && (viewerRole === 'OWNER' || viewerRole === 'ADMIN');
                return (
                  <div
                    key={member.id}
                    className="flex items-center gap-2.5 p-2 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors"
                  >
                    <UserAvatar user={member.user} size="sm" />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                        {member.user.displayName || member.user.username}
                        {isSelf && <span className="text-slate-400 font-normal"> (You)</span>}
                      </p>
                      <p className="text-[11px] text-slate-400 truncate">{ROLE_LABEL[targetRole]}</p>
                    </div>
                    {showMenuTrigger && (
                      <div className="relative flex-shrink-0">
                        <button
                          onClick={() => setOpenMenuUserId(openMenuUserId === member.user.id ? null : member.user.id)}
                          className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
                          aria-label="Member actions"
                        >
                          <MoreVertical className="w-4 h-4" />
                        </button>
                        {openMenuUserId === member.user.id && (
                          <GroupMemberActionsMenu
                            viewerRole={viewerRole}
                            targetRole={targetRole}
                            busy={busyUserId === member.user.id}
                            onPromote={() => runAction(member, () => groupApi.changeRole(group.id, member.user.id, 'ADMIN'))}
                            onDemote={() => runAction(member, () => groupApi.changeRole(group.id, member.user.id, 'MEMBER'))}
                            onRemove={() => {
                              setOpenMenuUserId(null);
                              setRemoveTarget(member);
                            }}
                            onTransferOwnership={() => {
                              setOpenMenuUserId(null);
                              setTransferTarget(member);
                            }}
                            onClose={() => setOpenMenuUserId(null)}
                          />
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          ))
        )}
      </div>

      {removeTarget && (
        <RemoveMemberConfirmDialog
          memberName={removeTarget.user.displayName || removeTarget.user.username}
          removing={busyUserId === removeTarget.user.id}
          onCancel={() => setRemoveTarget(null)}
          onConfirm={handleConfirmRemove}
        />
      )}

      {transferTarget && (
        <TransferOwnershipConfirmDialog
          memberName={transferTarget.user.displayName || transferTarget.user.username}
          transferring={busyUserId === transferTarget.user.id}
          onCancel={() => setTransferTarget(null)}
          onConfirm={handleConfirmTransfer}
        />
      )}
    </div>
  );
};
