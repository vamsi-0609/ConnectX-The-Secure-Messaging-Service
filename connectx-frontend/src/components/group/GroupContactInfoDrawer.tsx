import React, { useEffect, useState } from 'react';
import { ArrowLeft, X, Users, Image, FileText, Link2, Pin, Bell, Settings, ShieldCheck, LogOut, Trash2, ChevronRight, Loader2 } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { ConversationMember, Group, User } from '../../types';
import { GroupAvatar } from './GroupAvatar';
import { UserAvatar } from '../common/UserAvatar';
import { ROLE_LABEL } from '../../utils/groupLabels';
import { groupErrorMessage } from '../../utils/groupErrorMessages';
import { GroupMembersScreen } from './GroupMembersScreen';
import { GroupSettingsScreen } from './GroupSettingsScreen';
import { AddMembersModal } from './AddMembersModal';
import { LeaveGroupConfirmDialog } from './LeaveGroupConfirmDialog';
import { DeleteGroupConfirmDialog } from './DeleteGroupConfirmDialog';
import { SettingsRow, SettingsSectionLabel } from '../common/SettingsPrimitives';

interface GroupContactInfoDrawerProps {
  group: Group | null;
  currentUser: User;
  onClose: () => void;
  onGroupUpdated: (group: Group) => void;
  onLeaveGroup: () => Promise<void>;
  onDeleteGroup: () => Promise<void>;
  onOpenInvitations: (groupId: number) => void;
}

type DrawerView = 'main' | 'members' | 'settings';

// Deliberately a separate component from ContactInfoDrawer (P2P), not a retrofit -- group info is
// structurally different (member roster/roles, settings, invitations) from a 1:1 relationship
// panel. Same visual language (shell, header, section labels), different information
// architecture: identity -> members -> media -> notifications -> settings entry -> security ->
// leave, vs. ContactInfoDrawer's identity -> encryption -> devices -> relationship -> block.
//
// Internal drill-down (main -> members / settings) instead of stacking further modals, per this
// stage's "avoid nested modal stacking" guidance -- each sub-view replaces the drawer's content and
// has its own back arrow, mirroring ContactInfoDrawer's own mobile back-arrow pattern.
export const GroupContactInfoDrawer: React.FC<GroupContactInfoDrawerProps> = ({
  group,
  currentUser,
  onClose,
  onGroupUpdated,
  onLeaveGroup,
  onDeleteGroup,
  onOpenInvitations,
}) => {
  const [view, setView] = useState<DrawerView>('main');
  const [members, setMembers] = useState<ConversationMember[]>([]);
  const [loadingMembers, setLoadingMembers] = useState(false);
  const [showAddMembers, setShowAddMembers] = useState(false);
  const [showLeaveConfirm, setShowLeaveConfirm] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);

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

  const handleConfirmDelete = async () => {
    setDeleting(true);
    try {
      await onDeleteGroup();
      setShowDeleteConfirm(false);
    } catch (err) {
      alert(groupErrorMessage(err, "Couldn't delete the group."));
    } finally {
      setDeleting(false);
    }
  };

  const previewMembers = members.slice(0, 3);
  const canAddMembers =
    group.currentUserRole === 'OWNER' || group.currentUserRole === 'ADMIN' || group.whoCanInvite === 'ALL_MEMBERS';

  // AddMembersModal (and, on the main view, the leave/delete confirm dialogs) are rendered once,
  // below, regardless of which sub-view is active -- they're full-screen overlays, not part of any
  // one view's own layout. Previously each sub-view (members/settings) returned early, so opening
  // "Add members" from the Members screen set showAddMembers but the modal keyed off it lived only
  // in the 'main' view's JSX and never rendered; using view === 'members' / 'settings' as branches
  // in a single return (instead of three separate early returns) fixes that without changing what
  // each view itself renders.
  return (
    <>
      {view === 'members' && (
        <GroupMembersScreen
          group={group}
          currentUserId={currentUser.id}
          onBack={() => setView('main')}
          onOpenAddMembers={() => setShowAddMembers(true)}
          onMembersChanged={handleMembersChanged}
        />
      )}

      {view === 'settings' && (
        <GroupSettingsScreen
          group={group}
          onBack={() => setView('main')}
          onGroupUpdated={onGroupUpdated}
          onOpenMembers={() => setView('members')}
          onOpenInvitations={() => onOpenInvitations(group.id)}
        />
      )}

      {view === 'main' && (
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
          <SettingsSectionLabel>Members</SettingsSectionLabel>
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

      {/* Media/files/links and pinned messages are placeholders -- no group-scoped media gallery
          or pinned-message list exists yet; these are integration points for a future stage, not
          functional screens. */}
      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1">
        <SettingsSectionLabel>Media</SettingsSectionLabel>
        <SettingsRow icon={<Image className="w-4 h-4" />} label="Media" badge="Soon" disabled />
        <SettingsRow icon={<FileText className="w-4 h-4" />} label="Files" badge="Soon" disabled />
        <SettingsRow icon={<Link2 className="w-4 h-4" />} label="Links" badge="Soon" disabled />
        <SettingsRow icon={<Pin className="w-4 h-4" />} label="Pinned messages" badge="Soon" disabled />
      </div>

      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1">
        <SettingsSectionLabel>Notifications</SettingsSectionLabel>
        <SettingsRow icon={<Bell className="w-4 h-4" />} label="Group notifications" badge="Soon" disabled />
      </div>

      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80">
        <SettingsRow
          icon={<Settings className="w-4 h-4" />}
          label="Group Settings"
          onClick={() => setView('settings')}
        />
      </div>

      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1.5">
        <div className="flex items-center gap-2 text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-wider">
          <ShieldCheck className="w-4 h-4" />
          <span>Privacy &amp; security</span>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          🔒 Messages are private. Only current members can access messages in this group.
        </p>
      </div>

      <div className="p-4 space-y-2">
        <SettingsSectionLabel>Danger zone</SettingsSectionLabel>
        {group.currentUserRole === 'OWNER' ? (
          // The owner's only exit is deleting the group -- ownership transfer doesn't exist yet,
          // so "Leave group" is never shown to an owner (see LeaveGroupConfirmDialog usage below,
          // which an owner can never reach).
          <SettingsRow
            icon={<Trash2 className="w-4 h-4" />}
            label="Delete group"
            danger
            onClick={() => setShowDeleteConfirm(true)}
          />
        ) : (
          <SettingsRow
            icon={<LogOut className="w-4 h-4" />}
            label="Leave group"
            danger
            onClick={() => setShowLeaveConfirm(true)}
          />
        )}
      </div>
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

      {showDeleteConfirm && (
        <DeleteGroupConfirmDialog
          groupName={group.name}
          deleting={deleting}
          onCancel={() => setShowDeleteConfirm(false)}
          onConfirm={handleConfirmDelete}
        />
      )}
    </>
  );
};
