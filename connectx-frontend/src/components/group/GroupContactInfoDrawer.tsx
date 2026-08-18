import React, { useEffect, useState } from 'react';
import { ArrowLeft, X, Users, Image, Pin, Settings, ShieldCheck, LogOut, ChevronRight, Loader2 } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { ConversationMember, Group, User } from '../../types';
import { GroupAvatar } from './GroupAvatar';
import { UserAvatar } from '../common/UserAvatar';
import { ROLE_LABEL } from '../../utils/groupLabels';
import { GroupMembersScreen } from './GroupMembersScreen';
import { GroupSettingsScreen } from './GroupSettingsScreen';
import { AddMembersModal } from './AddMembersModal';
import { LeaveGroupConfirmDialog } from './LeaveGroupConfirmDialog';

interface GroupContactInfoDrawerProps {
  group: Group | null;
  currentUser: User;
  onClose: () => void;
  onGroupUpdated: (group: Group) => void;
  onUserUpdated: (user: User) => void;
  onLeaveGroup: () => Promise<void>;
  onOpenInvitations: (groupId: number) => void;
}

type DrawerView = 'main' | 'members' | 'settings';

// Deliberately a separate component from ContactInfoDrawer (P2P), not a retrofit -- group info is
// structurally different (member roster/roles, settings, invitations) from a 1:1 relationship
// panel. Internal drill-down (main -> members / settings) instead of stacking further modals, per
// this stage's "avoid nested modal stacking" guidance -- each sub-view replaces the drawer's
// content and has its own back arrow, mirroring ContactInfoDrawer's own mobile back-arrow pattern.
export const GroupContactInfoDrawer: React.FC<GroupContactInfoDrawerProps> = ({
  group,
  currentUser,
  onClose,
  onGroupUpdated,
  onUserUpdated,
  onLeaveGroup,
  onOpenInvitations,
}) => {
  const [view, setView] = useState<DrawerView>('main');
  const [members, setMembers] = useState<ConversationMember[]>([]);
  const [loadingMembers, setLoadingMembers] = useState(false);
  const [showAddMembers, setShowAddMembers] = useState(false);
  const [showLeaveConfirm, setShowLeaveConfirm] = useState(false);
  const [leaving, setLeaving] = useState(false);

  const refreshMembers = async (groupId: number) => {
    setLoadingMembers(true);
    try {
      const data = await groupApi.getGroupMembers(groupId);
      setMembers(data);
    } catch {
      setMembers([]);
    } finally {
      setLoadingMembers(false);
    }
  };

  const refreshGroup = async (groupId: number) => {
    try {
      const updated = await groupApi.getGroup(groupId);
      onGroupUpdated(updated);
    } catch {
      // best-effort -- the drawer still works with the last-known group snapshot
    }
  };

  useEffect(() => {
    setView('main');
    if (group) {
      refreshMembers(group.id);
    } else {
      setMembers([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [group?.id]);

  if (!group) return null;

  const handleMembersChanged = () => {
    refreshMembers(group.id);
    refreshGroup(group.id);
  };

  const handleConfirmLeave = async () => {
    setLeaving(true);
    try {
      await onLeaveGroup();
      setShowLeaveConfirm(false);
    } catch (err) {
      alert(err instanceof Error ? err.message : "Couldn't leave the group.");
    } finally {
      setLeaving(false);
    }
  };

  if (view === 'members') {
    return (
      <GroupMembersScreen
        group={group}
        currentUserId={currentUser.id}
        onBack={() => setView('main')}
        onOpenAddMembers={() => setShowAddMembers(true)}
        onMembersChanged={handleMembersChanged}
      />
    );
  }

  if (view === 'settings') {
    return (
      <GroupSettingsScreen
        group={group}
        currentUser={currentUser}
        onBack={() => setView('main')}
        onGroupUpdated={onGroupUpdated}
        onUserUpdated={onUserUpdated}
        onOpenMembers={() => setView('members')}
        onOpenInvitations={() => onOpenInvitations(group.id)}
        onLeaveGroup={() => setShowLeaveConfirm(true)}
      />
    );
  }

  const previewMembers = members.slice(0, 3);
  const canAddMembers =
    group.currentUserRole === 'OWNER' || group.currentUserRole === 'ADMIN' || group.whoCanInvite === 'ALL_MEMBERS';

  return (
    <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-80 h-full bg-white dark:bg-slate-900 border-l border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0 transition-colors duration-300 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none">
      <div className="h-16 px-4 border-b border-slate-200 dark:border-slate-800/80 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <button
            onClick={onClose}
            className="md:hidden p-1.5 -ml-1.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            aria-label="Back"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <h3 className="font-bold text-base">Group Info</h3>
        </div>
        <button
          onClick={onClose}
          className="hidden md:block p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          aria-label="Close"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      <div className="p-6 text-center border-b border-slate-200 dark:border-slate-800/80 space-y-3">
        <GroupAvatar name={group.name} avatarUrl={group.avatarUrl} size="xl" className="mx-auto shadow-xl" />
        <div>
          <h2 className="text-lg font-bold">{group.name}</h2>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            {group.activeMemberCount} {group.activeMemberCount === 1 ? 'member' : 'members'}
          </p>
          {group.description && (
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-2 leading-relaxed">{group.description}</p>
          )}
        </div>
      </div>

      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-2.5">
        <div className="flex items-center justify-between">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">Members</p>
          {canAddMembers && (
            <button
              onClick={() => setShowAddMembers(true)}
              className="text-[11px] font-semibold text-indigo-500 hover:text-indigo-600 dark:hover:text-indigo-400"
            >
              + Add members
            </button>
          )}
        </div>

        {loadingMembers ? (
          <div className="flex items-center justify-center py-4 text-slate-400">
            <Loader2 className="w-4 h-4 animate-spin" />
          </div>
        ) : (
          <div className="space-y-1">
            {previewMembers.map((member) => (
              <div key={member.id} className="flex items-center gap-2.5 py-1">
                <UserAvatar user={member.user} size="xs" viewable={false} />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                    {member.user.displayName || member.user.username}
                    {member.user.id === currentUser.id && <span className="text-slate-400 font-normal"> (You)</span>}
                  </p>
                </div>
                <span className="text-[11px] text-slate-400 flex-shrink-0">{ROLE_LABEL[member.role ?? 'MEMBER']}</span>
              </div>
            ))}
          </div>
        )}

        <button
          onClick={() => setView('members')}
          className="w-full flex items-center justify-between px-1 py-2 text-sm font-medium text-indigo-500 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
        >
          <span className="flex items-center gap-2">
            <Users className="w-4 h-4" /> View all members
          </span>
          <ChevronRight className="w-4 h-4" />
        </button>
      </div>

      <div className="border-b border-slate-200 dark:border-slate-800/80">
        {/* Media/files and pinned messages are placeholders for now -- no group-scoped media
            gallery or pinned-message list exists yet; these are integration points for a future
            stage, not functional screens. */}
        <button
          disabled
          className="w-full flex items-center gap-3 px-4 py-3 text-sm text-slate-400 cursor-not-allowed"
        >
          <Image className="w-4 h-4 flex-shrink-0" />
          <span className="flex-1 text-left">Media &amp; files</span>
          <span className="text-[10px] uppercase tracking-wide">Soon</span>
        </button>
        <button
          disabled
          className="w-full flex items-center gap-3 px-4 py-3 text-sm text-slate-400 cursor-not-allowed"
        >
          <Pin className="w-4 h-4 flex-shrink-0" />
          <span className="flex-1 text-left">Pinned messages</span>
          <span className="text-[10px] uppercase tracking-wide">Soon</span>
        </button>
      </div>

      <button
        onClick={() => setView('settings')}
        className="w-full flex items-center gap-3 px-4 py-3.5 text-sm font-medium text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors border-b border-slate-200 dark:border-slate-800/80"
      >
        <Settings className="w-4 h-4 text-indigo-400 flex-shrink-0" />
        <span className="flex-1 text-left">Group Settings</span>
        <ChevronRight className="w-4 h-4 text-slate-400" />
      </button>

      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1.5">
        <div className="flex items-center gap-2 text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-wider">
          <ShieldCheck className="w-4 h-4" />
          <span>Privacy &amp; security</span>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          🔒 Messages are private. Only current members can access messages in this group.
        </p>
      </div>

      {group.currentUserRole !== 'OWNER' && (
        <div className="p-4">
          <button
            onClick={() => setShowLeaveConfirm(true)}
            className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-red-600 dark:text-red-400 border border-red-200 dark:border-red-900/50 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors"
          >
            <LogOut className="w-4 h-4" />
            Leave Group
          </button>
        </div>
      )}

      {showAddMembers && (
        <AddMembersModal
          group={group}
          currentUserId={currentUser.id}
          existingMemberUserIds={new Set(members.map((m) => m.user.id))}
          onClose={() => setShowAddMembers(false)}
          onDone={handleMembersChanged}
        />
      )}

      {showLeaveConfirm && (
        <LeaveGroupConfirmDialog
          groupName={group.name}
          leaving={leaving}
          onCancel={() => setShowLeaveConfirm(false)}
          onConfirm={handleConfirmLeave}
        />
      )}
    </div>
  );
};
