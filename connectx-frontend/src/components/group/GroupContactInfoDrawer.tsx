import React, { useEffect, useState } from 'react';
import {
  ArrowLeft,
  X,
  Users,
  Settings,
  ShieldCheck,
  LogOut,
  Trash2,
  ChevronRight,
  Loader2,
  UserPlus,
  Pencil,
} from 'lucide-react';
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
import { EditGroupModal } from './EditGroupModal';
import { SettingsRow, SettingsSectionLabel } from '../common/SettingsPrimitives';

// Mirrors GroupAuthorizationService#requireCanEditGroupInfo exactly (OWNER_ADMIN_ONLY -> only
// OWNER/ADMIN; ALL_MEMBERS -> any active member) -- UI-only, the backend re-enforces this
// identically and independently on every PATCH/avatar request regardless of what this returns.
const canEditGroupInfo = (group: Group): boolean =>
  group.whoCanEditGroupInfo === 'ALL_MEMBERS' || group.currentUserRole === 'OWNER' || group.currentUserRole === 'ADMIN';

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
  const [showEditGroup, setShowEditGroup] = useState(false);

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
      // best-effort
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
          {/* Header */}
          <div className="h-16 px-4 border-b border-slate-200 dark:border-slate-800/80 flex items-center justify-between flex-shrink-0">
            <div className="flex items-center gap-2">
              <button
                onClick={onClose}
                className="md:hidden p-1.5 -ml-1.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                aria-label="Back"
              >
                <ArrowLeft className="w-5 h-5" />
              </button>
              <h3 className="font-bold text-base">Group Info</h3>
            </div>
            <button
              onClick={onClose}
              className="hidden md:block p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              aria-label="Close"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Group Identity */}
          <div className="p-6 text-center border-b border-slate-200 dark:border-slate-800/80 space-y-3 flex-shrink-0 bg-slate-50/40 dark:bg-slate-900/40">
            <GroupAvatar name={group.name} avatarUrl={group.avatarUrl} size="xl" className="mx-auto shadow-xl" />
            <div>
              <h2 className="text-lg font-bold tracking-tight">{group.name}</h2>
              <p className="text-xs text-violet-600 dark:text-violet-400 font-medium mt-0.5">
                {group.activeMemberCount} {group.activeMemberCount === 1 ? 'member' : 'members'}
              </p>
              {group.description && (
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-2 leading-relaxed px-2">
                  {group.description}
                </p>
              )}
            </div>
            {canEditGroupInfo(group) && (
              <button
                type="button"
                onClick={() => setShowEditGroup(true)}
                className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs font-semibold bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 hover:bg-indigo-500/20 transition-colors"
              >
                <Pencil className="w-3.5 h-3.5" /> Edit
              </button>
            )}
          </div>

          {/* Members Section */}
          <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-3">
            <div className="flex items-center justify-between">
              <SettingsSectionLabel>Members ({group.activeMemberCount})</SettingsSectionLabel>
              {canAddMembers && (
                <button
                  onClick={() => setShowAddMembers(true)}
                  className="text-xs font-semibold text-violet-600 dark:text-violet-400 hover:text-violet-500 flex items-center gap-1 transition-colors"
                >
                  <UserPlus className="w-3.5 h-3.5" />
                  Add
                </button>
              )}
            </div>

            {loadingMembers ? (
              <div className="flex items-center justify-center py-4 text-slate-400">
                <Loader2 className="w-4 h-4 animate-spin" />
              </div>
            ) : (
              <div className="space-y-1.5">
                {previewMembers.map((member) => (
                  <div
                    key={member.id}
                    className="flex items-center gap-3 p-2 rounded-xl bg-slate-50/60 dark:bg-slate-800/30 border border-slate-100 dark:border-slate-800/50"
                  >
                    <UserAvatar user={member.user} size="xs" viewable={false} />
                    <div className="min-w-0 flex-1">
                      <p className="text-xs font-semibold text-slate-800 dark:text-slate-100 truncate">
                        {member.user.displayName || member.user.username}
                        {member.user.id === currentUser.id && (
                          <span className="text-slate-400 font-normal"> (You)</span>
                        )}
                      </p>
                    </div>
                    <span className="text-[10px] font-semibold uppercase tracking-wider px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400">
                      {ROLE_LABEL[member.role ?? 'MEMBER']}
                    </span>
                  </div>
                ))}
              </div>
            )}

            <button
              onClick={() => setView('members')}
              className="w-full flex items-center justify-between p-2 text-xs font-semibold text-violet-600 dark:text-violet-400 hover:bg-violet-500/10 rounded-xl transition-colors"
            >
              <span className="flex items-center gap-2">
                <Users className="w-4 h-4" /> View all members
              </span>
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>

          {/* Group Settings */}
          <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1">
            <SettingsSectionLabel>Group Administration</SettingsSectionLabel>
            <SettingsRow
              icon={<Settings className="w-4 h-4" />}
              label="Group Settings"
              sublabel="Permissions & invitations"
              onClick={() => setView('settings')}
            />
          </div>

          {/* Privacy & Security */}
          <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-1.5">
            <div className="flex items-center gap-2 text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-wider">
              <ShieldCheck className="w-4 h-4" />
              <span>Privacy &amp; Security</span>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
              Messages and media in this group are private. Only confirmed group members can access conversation content.
            </p>
          </div>

          {/* Danger Zone */}
          <div className="p-4 space-y-2 flex-1">
            <SettingsSectionLabel>Danger Zone</SettingsSectionLabel>
            {group.currentUserRole === 'OWNER' ? (
              <SettingsRow
                icon={<Trash2 className="w-4 h-4" />}
                label="Delete group"
                sublabel="Permanently delete group and conversations"
                danger
                onClick={() => setShowDeleteConfirm(true)}
              />
            ) : (
              <SettingsRow
                icon={<LogOut className="w-4 h-4" />}
                label="Leave group"
                sublabel="Exit this group conversation"
                danger
                onClick={() => setShowLeaveConfirm(true)}
              />
            )}
          </div>
        </div>
      )}

      {showEditGroup && (
        <EditGroupModal group={group} onClose={() => setShowEditGroup(false)} onGroupUpdated={onGroupUpdated} />
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

