import React, { useState } from 'react';
import {
  Settings,
  X,
  ArrowLeft,
  Eye,
  Users,
  Download,
  Loader2,
  User as UserIcon,
  Bell,
  Smartphone,
  ShieldOff,
  Info,
} from 'lucide-react';
import { GroupAddPrivacy, ProfilePhotoVisibility, User } from '../../types';
import { userApi } from '../../api/userApi';
import { GROUP_ADD_PRIVACY_OPTIONS } from '../../utils/groupLabels';
import { SettingsDropdown, SettingsRow, SettingsSectionLabel } from '../common/SettingsPrimitives';
import { ConnectXLogo } from '../common/ConnectXLogo';

interface SettingsModalProps {
  currentUser: User;
  onClose: () => void;
  onUserUpdated: (user: User) => void;
  onExportChat: () => Promise<void>;
  exportingChat: boolean;
  // Display name of the currently open DIRECT conversation's other participant, if any --
  // Export Chat here always acts on whatever chat is currently open, same as the ChatHeader
  // menu's copy of this action.
  activeDirectChatName?: string | null;
  onOpenProfile: () => void;
  onOpenDevices: () => void;
  onOpenBlockedUsers: () => void;
}

const VISIBILITY_OPTIONS: { value: ProfilePhotoVisibility; label: string; description: string }[] = [
  { value: 'EVERYONE', label: 'Everyone', description: 'Any ConnectX user can see your profile photo' },
  { value: 'CONNECTIONS', label: 'Connections', description: 'Only people you\'re connected with can see it' },
];

type SettingsView = 'main' | 'privacy-groups' | 'about';

/**
 * The single sectioned Settings hub -- PROFILE / PRIVACY / NOTIFICATIONS / CHATS / DEVICES /
 * SAFETY / ABOUT. Internal drill-down (main -> a sub-view, e.g. Privacy > Groups) replaces this
 * modal's own content rather than opening a second stacked modal, matching the same
 * "avoid nested modal stacking" convention as GroupContactInfoDrawer's Members/Settings drill-down.
 * <p>
 * IMPORTANT: "Who can add me to groups?" (Settings > Privacy > Groups) is the ONLY place this
 * account-level preference appears. It is a global user privacy setting (User.groupAddPrivacy),
 * structurally unrelated to any single group's "Who can add members?" permission (which lives in
 * GroupSettingsScreen instead, per-group). The two must never be merged or shown in the same place
 * -- see groupLabels.ts's GROUP_ADD_PRIVACY_OPTIONS vs. WHO_CAN_INVITE_OPTIONS for the parallel
 * split on the labeling side.
 */
export const SettingsModal: React.FC<SettingsModalProps> = ({
  currentUser,
  onClose,
  onUserUpdated,
  onExportChat,
  exportingChat,
  activeDirectChatName,
  onOpenProfile,
  onOpenDevices,
  onOpenBlockedUsers,
}) => {
  const [view, setView] = useState<SettingsView>('main');
  const [visibility, setVisibility] = useState<ProfilePhotoVisibility>(currentUser.profilePhotoVisibility || 'EVERYONE');
  const [savingVisibility, setSavingVisibility] = useState(false);
  const [savingGroupPrivacy, setSavingGroupPrivacy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const handleSelectVisibility = async (next: ProfilePhotoVisibility) => {
    if (next === visibility || savingVisibility) return;
    const previous = visibility;
    setVisibility(next);
    setSavingVisibility(true);
    setError(null);
    setSuccessMsg(null);
    try {
      const updatedUser = await userApi.updateProfile({ profilePhotoVisibility: next });
      onUserUpdated(updatedUser);
      setSuccessMsg('Privacy setting saved');
    } catch (err: unknown) {
      setVisibility(previous);
      const message = err instanceof Error ? err.message : 'Failed to save setting';
      setError(message);
    } finally {
      setSavingVisibility(false);
    }
  };

  const handleSelectGroupPrivacy = async (next: GroupAddPrivacy) => {
    if (savingGroupPrivacy) return;
    setSavingGroupPrivacy(true);
    setError(null);
    setSuccessMsg(null);
    try {
      const updatedUser = await userApi.updateProfile({ groupAddPrivacy: next });
      onUserUpdated(updatedUser);
      setSuccessMsg('Privacy setting saved');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to save setting';
      setError(message);
    } finally {
      setSavingGroupPrivacy(false);
    }
  };

  const headerFor = (): { title: string; subtitle: string } => {
    if (view === 'privacy-groups') return { title: 'Groups', subtitle: 'Privacy' };
    if (view === 'about') return { title: 'About', subtitle: 'ConnectX' };
    return { title: 'Settings', subtitle: 'Privacy and chat preferences' };
  };
  const header = headerFor();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white max-h-[85vh] overflow-y-auto">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            {view !== 'main' ? (
              <button
                onClick={() => setView('main')}
                className="p-2 -ml-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
                aria-label="Back"
              >
                <ArrowLeft className="w-5 h-5" />
              </button>
            ) : (
              <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-500">
                <Settings className="w-5 h-5" />
              </div>
            )}
            <div>
              <h2 className="text-lg font-bold">{header.title}</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">{header.subtitle}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
            aria-label="Close settings"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {view === 'privacy-groups' && (
          <SettingsDropdown<GroupAddPrivacy>
            icon={<Users className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
            label="Who can add me to groups?"
            options={GROUP_ADD_PRIVACY_OPTIONS}
            value={currentUser.groupAddPrivacy ?? 'ANYONE'}
            editable
            saving={savingGroupPrivacy}
            onSelect={handleSelectGroupPrivacy}
          />
        )}

        {view === 'about' && (
          <div className="space-y-4 text-center py-2">
            <ConnectXLogo size="lg" variant="gradient" className="mx-auto" />
            <div>
              <h3 className="text-base font-bold">ConnectX</h3>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">End-to-end encrypted messaging</p>
            </div>
            <p className="text-[11px] text-slate-400">Version 1.0.0</p>
          </div>
        )}

        {view === 'main' && (
          <>
            <div className="space-y-2">
              <SettingsSectionLabel>Profile</SettingsSectionLabel>
              <SettingsRow
                icon={<UserIcon className="w-4 h-4" />}
                label="Profile"
                sublabel={currentUser.displayName || currentUser.username}
                onClick={() => {
                  onClose();
                  onOpenProfile();
                }}
              />
            </div>

            <div className="space-y-2">
              <SettingsSectionLabel>Privacy</SettingsSectionLabel>
              <div className="space-y-2.5">
                <SettingsDropdown<ProfilePhotoVisibility>
                  icon={<Eye className="w-4 h-4 text-indigo-400 flex-shrink-0" />}
                  label="Profile photo visibility"
                  options={VISIBILITY_OPTIONS}
                  value={visibility}
                  editable
                  saving={savingVisibility}
                  onSelect={handleSelectVisibility}
                />
                <SettingsRow
                  icon={<Users className="w-4 h-4" />}
                  label="Groups"
                  sublabel="Who can add me to groups?"
                  onClick={() => setView('privacy-groups')}
                />
              </div>
              {error && <p className="text-xs text-rose-500 font-medium px-1">{error}</p>}
              {successMsg && <p className="text-xs text-emerald-500 font-medium px-1">{successMsg}</p>}
            </div>

            <div className="space-y-2">
              <SettingsSectionLabel>Notifications</SettingsSectionLabel>
              <SettingsRow icon={<Bell className="w-4 h-4" />} label="Message notifications" badge="Soon" disabled />
            </div>

            <div className="space-y-2">
              <SettingsSectionLabel>Chats</SettingsSectionLabel>
              <button
                type="button"
                onClick={onExportChat}
                disabled={exportingChat || !activeDirectChatName}
                className="w-full px-3.5 py-3 flex items-center justify-between bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl text-left disabled:opacity-50 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              >
                <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                  {exportingChat ? (
                    <Loader2 className="w-4 h-4 text-indigo-400 animate-spin flex-shrink-0" />
                  ) : (
                    <Download className="w-4 h-4 text-indigo-400 flex-shrink-0" />
                  )}
                  <span>
                    Export chat
                    <span className="block text-[11px] font-normal text-slate-400 mt-0.5">
                      {exportingChat
                        ? 'Preparing export...'
                        : activeDirectChatName
                        ? `Download your chat with ${activeDirectChatName} as .txt`
                        : 'Open a direct chat to export it'}
                    </span>
                  </span>
                </span>
              </button>
            </div>

            <div className="space-y-2">
              <SettingsSectionLabel>Devices</SettingsSectionLabel>
              <SettingsRow
                icon={<Smartphone className="w-4 h-4" />}
                label="Devices"
                onClick={() => {
                  onClose();
                  onOpenDevices();
                }}
              />
            </div>

            <div className="space-y-2">
              <SettingsSectionLabel>Safety</SettingsSectionLabel>
              <SettingsRow
                icon={<ShieldOff className="w-4 h-4" />}
                label="Blocked users"
                onClick={() => {
                  onClose();
                  onOpenBlockedUsers();
                }}
              />
            </div>

            <div className="space-y-2">
              <SettingsSectionLabel>About</SettingsSectionLabel>
              <SettingsRow icon={<Info className="w-4 h-4" />} label="About ConnectX" onClick={() => setView('about')} />
            </div>
          </>
        )}
      </div>
    </div>
  );
};
