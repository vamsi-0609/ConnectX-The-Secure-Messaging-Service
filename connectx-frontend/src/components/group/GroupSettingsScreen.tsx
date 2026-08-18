import React, { useState } from 'react';
import { ArrowLeft, MessageSquare, UserPlus, Pencil, ChevronDown, Check, Loader2, Users, Mail, LogOut, Lock } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { userApi } from '../../api/userApi';
import { Group, GroupAddPrivacy, User, WhoCanEditGroupInfo, WhoCanInvite, WhoCanSendMessages } from '../../types';
import {
  GROUP_ADD_PRIVACY_OPTIONS,
  WHO_CAN_EDIT_GROUP_INFO_OPTIONS,
  WHO_CAN_INVITE_OPTIONS,
  WHO_CAN_SEND_MESSAGES_OPTIONS,
  whoCanEditGroupInfoLabel,
  whoCanInviteLabel,
  whoCanSendMessagesLabel,
} from '../../utils/groupLabels';
import { groupErrorMessage } from '../../utils/groupErrorMessages';

interface GroupSettingsScreenProps {
  group: Group;
  currentUser: User;
  onBack: () => void;
  onGroupUpdated: (group: Group) => void;
  onUserUpdated: (user: User) => void;
  onOpenMembers: () => void;
  onOpenInvitations: () => void;
  onLeaveGroup: () => void;
}

type SettingKey = 'whoCanSendMessages' | 'whoCanInvite' | 'whoCanEditGroupInfo';

// Generic labeled dropdown row -- same visual/interaction pattern as SettingsModal's profile-photo
// visibility control, reused three times here rather than three near-duplicate implementations.
function SettingDropdown<T extends string>({
  icon,
  label,
  options,
  value,
  editable,
  saving,
  onSelect,
}: {
  icon: React.ReactNode;
  label: string;
  options: { value: T; label: string; description: string }[];
  value: T;
  editable: boolean;
  saving: boolean;
  onSelect: (next: T) => void;
}) {
  const [open, setOpen] = useState(false);
  const selected = options.find((o) => o.value === value) ?? options[0];

  return (
    <div className="p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl space-y-2.5">
      <div className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
        {icon}
        {label}
      </div>
      {editable ? (
        <div className="relative">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            disabled={saving}
            className="w-full flex items-center justify-between px-3 py-2.5 bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 rounded-xl text-sm text-left disabled:opacity-60"
          >
            <span className="flex items-center gap-2">
              {saving && <Loader2 className="w-3.5 h-3.5 animate-spin text-indigo-500" />}
              {selected.label}
            </span>
            <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`} />
          </button>
          {open && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
              <div className="absolute left-0 right-0 top-full mt-1 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-20 overflow-hidden">
                {options.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => {
                      setOpen(false);
                      onSelect(option.value);
                    }}
                    className="w-full text-left px-3 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800 flex items-start gap-2"
                  >
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{option.label}</p>
                      <p className="text-[11px] text-slate-400">{option.description}</p>
                    </div>
                    {option.value === value && <Check className="w-4 h-4 text-indigo-500 flex-shrink-0 mt-0.5" />}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      ) : (
        <p className="text-sm text-slate-500 dark:text-slate-400">{selected.label}</p>
      )}
      <p className="text-[11px] text-slate-400">{selected.description}</p>
    </div>
  );
}

export const GroupSettingsScreen: React.FC<GroupSettingsScreenProps> = ({
  group,
  currentUser,
  onBack,
  onGroupUpdated,
  onUserUpdated,
  onOpenMembers,
  onOpenInvitations,
  onLeaveGroup,
}) => {
  const isOwner = group.currentUserRole === 'OWNER';
  const [saving, setSaving] = useState<SettingKey | null>(null);
  const [savingPrivacy, setSavingPrivacy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSelectSetting = async (key: SettingKey, next: string) => {
    if (!isOwner || saving) return;
    setSaving(key);
    setError(null);
    try {
      const updated = await groupApi.updateSettings(group.id, { [key]: next });
      onGroupUpdated(updated);
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't save that setting."));
    } finally {
      setSaving(null);
    }
  };

  const handleSelectPrivacy = async (next: GroupAddPrivacy) => {
    if (savingPrivacy) return;
    setSavingPrivacy(true);
    setError(null);
    try {
      const updated = await userApi.updateProfile({ groupAddPrivacy: next });
      onUserUpdated(updated);
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't save that setting."));
    } finally {
      setSavingPrivacy(false);
    }
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
        <h3 className="font-bold text-base">Group Settings</h3>
      </div>

      <div className="p-4 space-y-5">
        {!isOwner && (
          <p className="text-xs text-slate-400 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-xl px-3 py-2.5">
            Only the group owner can change these settings.
          </p>
        )}

        <div className="space-y-2">
          <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">Permissions</p>
          <div className="space-y-2.5">
            <SettingDropdown<WhoCanSendMessages>
              icon={<MessageSquare className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
              label="Who can send messages"
              options={WHO_CAN_SEND_MESSAGES_OPTIONS}
              value={group.whoCanSendMessages}
              editable={isOwner}
              saving={saving === 'whoCanSendMessages'}
              onSelect={(next) => handleSelectSetting('whoCanSendMessages', next)}
            />
            <SettingDropdown<WhoCanInvite>
              icon={<UserPlus className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
              label="Who can add members"
              options={WHO_CAN_INVITE_OPTIONS}
              value={group.whoCanInvite}
              editable={isOwner}
              saving={saving === 'whoCanInvite'}
              onSelect={(next) => handleSelectSetting('whoCanInvite', next)}
            />
            <SettingDropdown<WhoCanEditGroupInfo>
              icon={<Pencil className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
              label="Who can edit group info"
              options={WHO_CAN_EDIT_GROUP_INFO_OPTIONS}
              value={group.whoCanEditGroupInfo}
              editable={isOwner}
              saving={saving === 'whoCanEditGroupInfo'}
              onSelect={(next) => handleSelectSetting('whoCanEditGroupInfo', next)}
            />
          </div>
        </div>

        <div className="space-y-2">
          <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">Members</p>
          <button
            onClick={onOpenMembers}
            className="w-full flex items-center gap-3 p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl text-left hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <Users className="w-4 h-4 text-indigo-400 flex-shrink-0" />
            <span className="text-sm font-medium text-slate-700 dark:text-slate-200">Manage members</span>
          </button>
          <button
            onClick={onOpenInvitations}
            className="w-full flex items-center gap-3 p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl text-left hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <Mail className="w-4 h-4 text-indigo-400 flex-shrink-0" />
            <span className="text-sm font-medium text-slate-700 dark:text-slate-200">Pending invitations</span>
          </button>
        </div>

        <div className="space-y-2">
          <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">Privacy</p>
          <SettingDropdown<GroupAddPrivacy>
            icon={<Lock className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
            label="Who can add you to groups"
            options={GROUP_ADD_PRIVACY_OPTIONS}
            value={currentUser.groupAddPrivacy ?? 'ANYONE'}
            editable
            saving={savingPrivacy}
            onSelect={handleSelectPrivacy}
          />
          <p className="px-1 text-[11px] text-slate-400">This applies to every group, not just this one.</p>
        </div>

        {error && <p className="text-xs text-rose-500 font-medium">{error}</p>}

        {group.currentUserRole !== 'OWNER' && (
          <button
            onClick={onLeaveGroup}
            className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-red-600 dark:text-red-400 border border-red-200 dark:border-red-900/50 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors"
          >
            <LogOut className="w-4 h-4" />
            Leave Group
          </button>
        )}
      </div>
    </div>
  );
};
