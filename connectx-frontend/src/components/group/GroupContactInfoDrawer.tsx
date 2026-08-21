import React, { useEffect, useState } from 'react';
import {
  ArrowLeft,
  Users,
  Settings,
  ShieldCheck,
  LogOut,
  Trash2,
  ChevronRight,
  Loader2,
  UserPlus,
  Pencil,
  Lock,
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
import { SettingsSectionLabel } from '../common/SettingsPrimitives';

// Mirrors GroupAuthorizationService#requireCanEditGroupInfo exactly (OWNER_ADMIN_ONLY -> only
// OWNER/ADMIN; ALL_MEMBERS -> any active member) -- UI-only, the backend re-enforces this
// identically and independently on every PATCH/avatar request regardless of what this returns.
const canEditGroupInfo = (group: Group): boolean =>
  group.whoCanEditGroupInfo === 'ALL_MEMBERS' ||
  group.currentUserRole === 'OWNER' ||
  group.currentUserRole === 'ADMIN';

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

  const currentMember = members.find((m) => m.user.id === currentUser.id);
  const effectiveRole = group.currentUserRole ?? currentMember?.role ?? 'MEMBER';
  const isOwner = effectiveRole === 'OWNER';
  const isAdmin = effectiveRole === 'ADMIN';

  const previewMembers = members.slice(0, 3);
  const canAddMembers =
    isOwner ||
    isAdmin ||
    group.whoCanInvite === 'ALL_MEMBERS';
  const canEditInfo =
    group.whoCanEditGroupInfo === 'ALL_MEMBERS' ||
    isOwner ||
    isAdmin;

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
        <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-[380px] lg:w-[420px] xl:w-[460px] h-full bg-white dark:bg-[#080b12] border-l border-slate-200/90 dark:border-slate-800/80 flex flex-col flex-shrink-0 transition-colors duration-200 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none relative">
          {/* Header */}
          <div className="h-16 px-4 border-b border-slate-200/80 dark:border-slate-800/80 flex items-center justify-between flex-shrink-0 bg-slate-50/50 dark:bg-[#0c101c]/60 backdrop-blur-sm">
            <div className="flex items-center gap-2">
              <button
                onClick={onClose}
                className="p-2 -ml-1 text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95 transition-all cursor-pointer"
                aria-label="Back"
              >
                <ArrowLeft className="w-5 h-5" />
              </button>
              <h3 className="font-bold text-base tracking-tight text-slate-900 dark:text-white">
                Group Info
              </h3>
            </div>
          </div>

          {/* Main Scrollable Content */}
          <div className="p-4 sm:p-5 lg:p-6 space-y-4 flex-1 flex flex-col min-h-0">
            {/* Group Identity Hero */}
            <div className="text-center pt-3 pb-1 space-y-2.5">
              <div className="relative inline-block mx-auto">
                <div className="rounded-full p-1 ring-2 ring-violet-500/80 shadow-xl bg-slate-100 dark:bg-[#080b12]">
                  <GroupAvatar
                    name={group.name}
                    avatarUrl={group.avatarUrl}
                    size="lg"
                    className="w-20 h-20 sm:w-24 sm:h-24"
                  />
                </div>
                <span
                  className="absolute bottom-0 right-0 w-6 h-6 rounded-full bg-violet-600 text-white flex items-center justify-center ring-2 ring-white dark:ring-[#080b12] shadow-md"
                  title="Group Conversation"
                >
                  <Users className="w-3.5 h-3.5" />
                </span>
              </div>

              <div>
                <h2 className="text-xl font-bold tracking-tight text-slate-900 dark:text-white">
                  {group.name}
                </h2>
                <p className="text-xs font-semibold text-violet-600 dark:text-violet-400 mt-1">
                  {group.activeMemberCount}{' '}
                  {group.activeMemberCount === 1 ? 'member' : 'members'}
                </p>
                {group.description && (
                  <p className="text-xs text-slate-600 dark:text-slate-400 mt-2 leading-relaxed max-w-sm mx-auto px-2 break-words">
                    {group.description}
                  </p>
                )}
              </div>
            </div>

            {/* Group Quick Management Actions */}
            <div className="flex gap-2 sm:gap-2.5 pt-1 [&>*]:flex-1">
              {canAddMembers && (
                <button
                  type="button"
                  onClick={() => setShowAddMembers(true)}
                  className="p-2.5 sm:p-3 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 hover:border-violet-500/40 dark:hover:border-violet-500/40 hover:bg-violet-50/50 dark:hover:bg-violet-950/20 active:scale-[0.98] transition-all flex flex-col items-center justify-center gap-1.5 text-center cursor-pointer group shadow-sm"
                >
                  <div className="w-9 h-9 sm:w-10 sm:h-10 rounded-full bg-violet-500/10 text-violet-600 dark:text-violet-400 flex items-center justify-center group-hover:scale-105 transition-transform">
                    <UserPlus className="w-4.5 h-4.5 sm:w-5 sm:h-5" />
                  </div>
                  <span className="text-xs font-medium text-slate-700 dark:text-slate-200 group-hover:text-violet-600 dark:group-hover:text-violet-400 leading-tight">
                    Add Members
                  </span>
                </button>
              )}

              <button
                type="button"
                onClick={() => setView('members')}
                className="p-2.5 sm:p-3 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 hover:border-violet-500/40 dark:hover:border-violet-500/40 hover:bg-violet-50/50 dark:hover:bg-violet-950/20 active:scale-[0.98] transition-all flex flex-col items-center justify-center gap-1.5 text-center cursor-pointer group shadow-sm"
              >
                <div className="w-9 h-9 sm:w-10 sm:h-10 rounded-full bg-violet-500/10 text-violet-600 dark:text-violet-400 flex items-center justify-center group-hover:scale-105 transition-transform">
                  <Users className="w-4.5 h-4.5 sm:w-5 sm:h-5" />
                </div>
                <span className="text-xs font-medium text-slate-700 dark:text-slate-200 group-hover:text-violet-600 dark:group-hover:text-violet-400 leading-tight">
                  Members
                </span>
              </button>

              {canEditInfo && (
                <button
                  type="button"
                  onClick={() => setShowEditGroup(true)}
                  className="p-2.5 sm:p-3 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 hover:border-violet-500/40 dark:hover:border-violet-500/40 hover:bg-violet-50/50 dark:hover:bg-violet-950/20 active:scale-[0.98] transition-all flex flex-col items-center justify-center gap-1.5 text-center cursor-pointer group shadow-sm"
                >
                  <div className="w-9 h-9 sm:w-10 sm:h-10 rounded-full bg-violet-500/10 text-violet-600 dark:text-violet-400 flex items-center justify-center group-hover:scale-105 transition-transform">
                    <Pencil className="w-4.5 h-4.5 sm:w-5 sm:h-5" />
                  </div>
                  <span className="text-xs font-medium text-slate-700 dark:text-slate-200 group-hover:text-violet-600 dark:group-hover:text-violet-400 leading-tight">
                    Edit Group
                  </span>
                </button>
              )}
            </div>

            {/* Members Preview Surface */}
            <div className="p-4 rounded-2xl bg-slate-50/90 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 space-y-3 shadow-sm">
              <div className="flex items-center justify-between">
                <SettingsSectionLabel>
                  Members ({group.activeMemberCount})
                </SettingsSectionLabel>
                {canAddMembers && (
                  <button
                    onClick={() => setShowAddMembers(true)}
                    className="text-xs font-semibold text-violet-600 dark:text-violet-400 hover:text-violet-500 flex items-center gap-1 transition-colors cursor-pointer"
                  >
                    <UserPlus className="w-3.5 h-3.5" />
                    <span>Add</span>
                  </button>
                )}
              </div>

              {loadingMembers ? (
                <div className="flex items-center justify-center py-4 text-slate-400">
                  <Loader2 className="w-5 h-5 animate-spin" />
                </div>
              ) : (
                <div className="space-y-2">
                  {previewMembers.map((member) => (
                    <div
                      key={member.id}
                      className="flex items-center gap-3 p-2.5 rounded-xl bg-white dark:bg-[#080b12]/80 border border-slate-200/60 dark:border-slate-800/60 transition-colors"
                    >
                      <UserAvatar
                        user={member.user}
                        size="xs"
                        viewable={false}
                        className="w-8 h-8 flex-shrink-0"
                      />
                      <div className="min-w-0 flex-1">
                        <p className="text-xs font-bold text-slate-900 dark:text-white truncate">
                          {member.user.displayName || member.user.username}
                          {member.user.id === currentUser.id && (
                            <span className="text-slate-400 font-normal"> (You)</span>
                          )}
                        </p>
                        <p className="text-[11px] text-slate-400 dark:text-slate-500 truncate">
                          @{member.user.username}
                        </p>
                      </div>
                      <span
                        className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md flex-shrink-0 ${
                          member.role === 'OWNER'
                            ? 'bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30'
                            : member.role === 'ADMIN'
                            ? 'bg-violet-500/15 text-violet-600 dark:text-violet-400 border border-violet-500/30'
                            : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 border border-slate-200/60 dark:border-slate-700/60'
                        }`}
                      >
                        {ROLE_LABEL[member.role ?? 'MEMBER']}
                      </span>
                    </div>
                  ))}
                </div>
              )}

              <button
                onClick={() => setView('members')}
                className="w-full flex items-center justify-between p-2.5 text-xs font-semibold text-violet-600 dark:text-violet-400 hover:bg-violet-500/10 rounded-xl transition-colors cursor-pointer"
              >
                <span className="flex items-center gap-2">
                  <Users className="w-4 h-4" />
                  <span>View all {group.activeMemberCount} members</span>
                </span>
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>

            {/* Group Administration Section */}
            <div className="p-4 rounded-2xl bg-slate-50/90 dark:bg-[#0c101c]/90 border border-slate-200/80 dark:border-slate-800/80 space-y-2 shadow-sm">
              <SettingsSectionLabel>Group Administration</SettingsSectionLabel>
              <button
                onClick={() => setView('settings')}
                className="w-full p-3 rounded-xl bg-white dark:bg-[#080b12]/80 border border-slate-200/60 dark:border-slate-800/60 hover:border-violet-500/40 hover:bg-slate-50 dark:hover:bg-slate-800/40 active:scale-[0.99] transition-all flex items-center justify-between gap-3 text-left cursor-pointer group"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <div className="w-9 h-9 rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-400 flex items-center justify-center flex-shrink-0">
                    <Settings className="w-4 h-4" />
                  </div>
                  <div className="min-w-0">
                    <h4 className="text-xs font-bold text-slate-900 dark:text-white group-hover:text-violet-600 dark:group-hover:text-violet-400 transition-colors">
                      Group Settings
                    </h4>
                    <p className="text-[11px] text-slate-500 dark:text-slate-400 truncate mt-0.5">
                      Permissions &amp; invitations
                    </p>
                  </div>
                </div>
                <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-violet-500 transition-colors flex-shrink-0" />
              </button>
            </div>

            {/* End-to-End Encryption Section */}
            <div className="p-4 rounded-2xl bg-slate-50/80 dark:bg-[#0c101c]/90 border border-violet-500/20 dark:border-violet-800/30 flex items-center gap-3.5 relative overflow-hidden flex-shrink-0 shadow-sm">
              <div className="w-11 h-11 rounded-full bg-violet-600/15 border border-violet-500/30 text-violet-500 dark:text-violet-400 flex items-center justify-center flex-shrink-0">
                <Lock className="w-5 h-5" />
              </div>
              <div className="min-w-0">
                <div className="flex items-center gap-1.5">
                  <h4 className="text-sm font-semibold text-slate-900 dark:text-white">
                    End-to-End Encrypted
                  </h4>
                  <ShieldCheck className="w-3.5 h-3.5 text-violet-500 dark:text-violet-400 flex-shrink-0" />
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mt-0.5">
                  Messages and media in this group are secured with end-to-end encryption. Only confirmed members of this group can access conversation content.
                </p>
              </div>
            </div>

            {/* Danger Zone */}
            <div className="mt-auto pt-2 p-4 rounded-2xl bg-rose-50/70 dark:bg-rose-950/20 border border-rose-200/80 dark:border-rose-900/40 space-y-2 flex-shrink-0">
              <span className="text-[11px] font-bold uppercase tracking-wider text-rose-600 dark:text-rose-400">
                Danger Zone
              </span>
              {isOwner ? (
                <button
                  onClick={() => setShowDeleteConfirm(true)}
                  disabled={deleting}
                  className="w-full p-3 rounded-xl bg-white/80 dark:bg-[#0c101c]/80 border border-rose-200/90 dark:border-rose-900/50 hover:bg-rose-100/70 dark:hover:bg-rose-950/50 active:scale-[0.99] transition-all flex items-center justify-between gap-3 text-left cursor-pointer group disabled:opacity-50"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="w-8 h-8 rounded-lg bg-rose-500/15 text-rose-600 dark:text-rose-400 flex items-center justify-center flex-shrink-0">
                      {deleting ? (
                        <Loader2 className="w-4 h-4 animate-spin" />
                      ) : (
                        <Trash2 className="w-4 h-4" />
                      )}
                    </div>
                    <div className="min-w-0">
                      <h4 className="text-xs font-bold text-rose-600 dark:text-rose-400">
                        Delete Group
                      </h4>
                      <p className="text-[11px] text-rose-500/80 dark:text-rose-400/70 truncate mt-0.5">
                        Permanently delete group and conversations
                      </p>
                    </div>
                  </div>
                  <ChevronRight className="w-4 h-4 text-rose-400 flex-shrink-0" />
                </button>
              ) : (
                <button
                  onClick={() => setShowLeaveConfirm(true)}
                  disabled={leaving}
                  className="w-full p-3 rounded-xl bg-white/80 dark:bg-[#0c101c]/80 border border-rose-200/90 dark:border-rose-900/50 hover:bg-rose-100/70 dark:hover:bg-rose-950/50 active:scale-[0.99] transition-all flex items-center justify-between gap-3 text-left cursor-pointer group disabled:opacity-50"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="w-8 h-8 rounded-lg bg-rose-500/15 text-rose-600 dark:text-rose-400 flex items-center justify-center flex-shrink-0">
                      {leaving ? (
                        <Loader2 className="w-4 h-4 animate-spin" />
                      ) : (
                        <LogOut className="w-4 h-4" />
                      )}
                    </div>
                    <div className="min-w-0">
                      <h4 className="text-xs font-bold text-rose-600 dark:text-rose-400">
                        Leave Group
                      </h4>
                      <p className="text-[11px] text-rose-500/80 dark:text-rose-400/70 truncate mt-0.5">
                        Exit this group conversation
                      </p>
                    </div>
                  </div>
                  <ChevronRight className="w-4 h-4 text-rose-400 flex-shrink-0" />
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {showEditGroup && (
        <EditGroupModal
          group={group}
          onClose={() => setShowEditGroup(false)}
          onGroupUpdated={onGroupUpdated}
        />
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
