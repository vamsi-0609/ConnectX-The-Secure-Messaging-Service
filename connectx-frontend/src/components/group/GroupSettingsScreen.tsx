import React, { useState } from 'react';
import { ArrowLeft, MessageSquare, UserPlus, Pencil, Users, Mail } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { Group, WhoCanEditGroupInfo, WhoCanInvite, WhoCanSendMessages } from '../../types';
import {
  WHO_CAN_EDIT_GROUP_INFO_OPTIONS,
  WHO_CAN_INVITE_OPTIONS,
  WHO_CAN_SEND_MESSAGES_OPTIONS,
} from '../../utils/groupLabels';
import { groupErrorMessage } from '../../utils/groupErrorMessages';
import { SettingsDropdown, SettingsRow, SettingsSectionLabel } from '../common/SettingsPrimitives';

interface GroupSettingsScreenProps {
  group: Group;
  onBack: () => void;
  onGroupUpdated: (group: Group) => void;
  onOpenMembers: () => void;
  onOpenInvitations: () => void;
}

type SettingKey = 'whoCanSendMessages' | 'whoCanEditGroupInfo' | 'whoCanInvite';

// Group-scoped permissions only -- "who can add members to THIS group" (whoCanInvite). The
// account-level "who can add me to groups?" preference lives in the global Settings screen
// (Settings > Privacy > Groups) and must never appear here -- see groupLabels.ts's note on
// GROUP_ADD_PRIVACY_OPTIONS for why the two are kept structurally separate.
export const GroupSettingsScreen: React.FC<GroupSettingsScreenProps> = ({
  group,
  onBack,
  onGroupUpdated,
  onOpenMembers,
  onOpenInvitations,
}) => {
  const isOwner = group.currentUserRole === 'OWNER';
  const [saving, setSaving] = useState<SettingKey | null>(null);
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

  return (
    <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-[380px] lg:w-[420px] xl:w-[460px] h-full bg-white dark:bg-[#080b12] border-l border-slate-200/90 dark:border-slate-800/80 flex flex-col flex-shrink-0 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none">
      <div className="h-16 px-4 border-b border-slate-200/80 dark:border-slate-800/80 flex items-center gap-2 flex-shrink-0 bg-slate-50/50 dark:bg-[#0c101c]/60 backdrop-blur-sm">
        <button
          onClick={onBack}
          className="p-2 -ml-1 text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95 transition-all cursor-pointer"
          aria-label="Back"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h3 className="font-bold text-base text-slate-900 dark:text-white">Group Settings</h3>
      </div>

      <div className="p-4 space-y-5">
        {!isOwner && (
          <p className="text-xs text-slate-400 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-xl px-3 py-2.5">
            Only the group owner can change these settings.
          </p>
        )}

        <div className="space-y-2">
          <SettingsSectionLabel>Permissions</SettingsSectionLabel>
          <div className="space-y-2.5">
            <SettingsDropdown<WhoCanSendMessages>
              icon={<MessageSquare className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
              label="Who can send messages?"
              options={WHO_CAN_SEND_MESSAGES_OPTIONS}
              value={group.whoCanSendMessages}
              editable={isOwner}
              saving={saving === 'whoCanSendMessages'}
              onSelect={(next) => handleSelectSetting('whoCanSendMessages', next)}
            />
            <SettingsDropdown<WhoCanEditGroupInfo>
              icon={<Pencil className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
              label="Who can edit group info?"
              options={WHO_CAN_EDIT_GROUP_INFO_OPTIONS}
              value={group.whoCanEditGroupInfo}
              editable={isOwner}
              saving={saving === 'whoCanEditGroupInfo'}
              onSelect={(next) => handleSelectSetting('whoCanEditGroupInfo', next)}
            />
            <SettingsDropdown<WhoCanInvite>
              icon={<UserPlus className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
              label="Who can add members?"
              options={WHO_CAN_INVITE_OPTIONS}
              value={group.whoCanInvite}
              editable={isOwner}
              saving={saving === 'whoCanInvite'}
              onSelect={(next) => handleSelectSetting('whoCanInvite', next)}
            />
          </div>
        </div>

        <div className="space-y-2">
          <SettingsSectionLabel>Members</SettingsSectionLabel>
          <div className="space-y-1">
            <SettingsRow icon={<Users className="w-4 h-4" />} label="Manage members" onClick={onOpenMembers} />
            <SettingsRow icon={<Mail className="w-4 h-4" />} label="Invitations" onClick={onOpenInvitations} />
          </div>
        </div>

        {error && <p className="text-xs text-rose-500 font-medium">{error}</p>}
      </div>
    </div>
  );
};
