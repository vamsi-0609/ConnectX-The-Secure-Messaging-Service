import React, { useState } from 'react';
import {
  Settings,
  X,
  ArrowLeft,
  Eye,
  Users,
  Bell,
  Info,
  Check,
  Loader2,
  Lock,
  ChevronRight,
  Sparkles,
  Palette,
} from 'lucide-react';
import { GroupAddPrivacy, ProfilePhotoVisibility, User } from '../../types';
import { userApi } from '../../api/userApi';
import { GROUP_ADD_PRIVACY_OPTIONS } from '../../utils/groupLabels';
import { SettingsSectionLabel } from '../common/SettingsPrimitives';
import { ConnectXLogo } from '../common/ConnectXLogo';
import { soundManager } from '../../utils/notificationSound';
import { browserNotifications } from '../../utils/browserNotifications';

interface SettingsModalProps {
  currentUser: User;
  onClose: () => void;
  onUserUpdated: (user: User) => void;
  notificationsEnabled?: boolean;
  onToggleNotifications?: () => void;
  // Optional backwards compatibility props
  onExportChat?: () => Promise<void>;
  exportingChat?: boolean;
  activeDirectChatName?: string | null;
  onOpenProfile?: () => void;
  onOpenDevices?: () => void;
  onOpenBlockedUsers?: () => void;
}

const VISIBILITY_OPTIONS: { value: ProfilePhotoVisibility; label: string; description: string }[] = [
  { value: 'EVERYONE', label: 'Everyone', description: 'Any ConnectX user can see your profile photo' },
  { value: 'CONNECTIONS', label: 'My connections', description: 'Only your confirmed connections can see it' },
];

type SettingsView = 'main' | 'privacy-groups' | 'about';

export const SettingsModal: React.FC<SettingsModalProps> = ({
  currentUser,
  onClose,
  onUserUpdated,
  notificationsEnabled: propNotificationsEnabled,
  onToggleNotifications,
}) => {
  const [view, setView] = useState<SettingsView>('main');
  const [visibility, setVisibility] = useState<ProfilePhotoVisibility>(currentUser.profilePhotoVisibility || 'EVERYONE');
  const [savingVisibility, setSavingVisibility] = useState(false);
  const [savingGroupPrivacy, setSavingGroupPrivacy] = useState(false);
  const [localNotificationsEnabled, setLocalNotificationsEnabled] = useState<boolean>(() => {
    if (typeof propNotificationsEnabled === 'boolean') return propNotificationsEnabled;
    return soundManager.isEnabled();
  });
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const isNotificationsOn = typeof propNotificationsEnabled === 'boolean'
    ? propNotificationsEnabled
    : localNotificationsEnabled;

  const handleToggleNotificationsClick = () => {
    if (onToggleNotifications) {
      onToggleNotifications();
    } else {
      const next = soundManager.toggle();
      browserNotifications.setEnabled(next);
      setLocalNotificationsEnabled(next);
    }
  };

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
      setSuccessMsg('Privacy preference updated');
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
      setSuccessMsg('Group privacy preference updated');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to save setting';
      setError(message);
    } finally {
      setSavingGroupPrivacy(false);
    }
  };

  const headerFor = (): { title: string; subtitle: string } => {
    if (view === 'privacy-groups') return { title: 'Groups', subtitle: 'Privacy settings' };
    if (view === 'about') return { title: 'About ConnectX', subtitle: 'Application information' };
    return { title: 'Settings', subtitle: 'Preferences & privacy' };
  };
  const header = headerFor();

  const currentGroupPrivacyLabel =
    GROUP_ADD_PRIVACY_OPTIONS.find((o) => o.value === (currentUser.groupAddPrivacy ?? 'ANYONE'))?.label ?? 'Everyone';

  const currentVisibilityLabel =
    VISIBILITY_OPTIONS.find((o) => o.value === visibility)?.label ?? 'Everyone';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-3 sm:p-4 animate-fadeIn select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl overflow-hidden flex flex-col max-h-[85vh] text-slate-900 dark:text-white animate-pop-in">
        {/* Header */}
        <div className="p-5 border-b border-slate-100 dark:border-slate-800/80 bg-slate-50/50 dark:bg-slate-900/50 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-3">
            {view !== 'main' ? (
              <button
                onClick={() => setView('main')}
                className="p-2 -ml-1 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-colors"
                aria-label="Back"
              >
                <ArrowLeft className="w-5 h-5" />
              </button>
            ) : (
              <div className="p-2 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400">
                <Settings className="w-5 h-5" />
              </div>
            )}
            <div>
              <h2 className="text-base sm:text-lg font-bold tracking-tight">{header.title}</h2>
              <p className="text-xs text-slate-400 dark:text-slate-500 font-medium">{header.subtitle}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-colors"
            aria-label="Close settings"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content */}
        <div className="p-5 overflow-y-auto space-y-5 flex-1">
          {error && (
            <div className="p-3 rounded-2xl bg-rose-500/10 border border-rose-500/20 text-xs text-rose-500 font-medium">
              {error}
            </div>
          )}
          {successMsg && (
            <div className="p-3 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-xs text-emerald-500 font-medium">
              {successMsg}
            </div>
          )}

          {/* VIEW: PRIVACY > GROUPS DRILL-DOWN */}
          {view === 'privacy-groups' && (
            <div className="space-y-4 animate-fadeIn">
              <div>
                <h3 className="text-sm font-bold text-slate-900 dark:text-white mb-1">
                  Who can add me to groups?
                </h3>
                <p className="text-xs text-slate-400 dark:text-slate-500 leading-relaxed">
                  Choose who is permitted to add you directly into group conversations on ConnectX.
                </p>
              </div>

              <div className="space-y-2">
                {GROUP_ADD_PRIVACY_OPTIONS.map((opt) => {
                  const isSelected = (currentUser.groupAddPrivacy ?? 'ANYONE') === opt.value;
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      disabled={savingGroupPrivacy}
                      onClick={() => handleSelectGroupPrivacy(opt.value)}
                      className={`w-full p-4 rounded-2xl border text-left transition-all flex items-start gap-3.5 ${
                        isSelected
                          ? 'border-violet-500/60 bg-violet-500/5 dark:bg-violet-500/10 shadow-sm'
                          : 'border-slate-200/80 dark:border-slate-800/80 hover:bg-slate-50 dark:hover:bg-slate-800/40'
                      }`}
                    >
                      <div
                        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center mt-0.5 flex-shrink-0 transition-colors ${
                          isSelected
                            ? 'border-violet-600 bg-violet-600 text-white'
                            : 'border-slate-300 dark:border-slate-600'
                        }`}
                      >
                        {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center justify-between">
                          <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">{opt.label}</p>
                          {savingGroupPrivacy && isSelected && (
                            <Loader2 className="w-3.5 h-3.5 animate-spin text-violet-500" />
                          )}
                        </div>
                        <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{opt.description}</p>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* VIEW: ABOUT DRILL-DOWN */}
          {view === 'about' && (
            <div className="space-y-5 text-center py-4 animate-fadeIn">
              <div className="w-20 h-20 mx-auto rounded-3xl bg-gradient-to-tr from-violet-600 via-indigo-600 to-violet-500 p-0.5 shadow-xl shadow-indigo-500/20 flex items-center justify-center">
                <div className="w-full h-full bg-white dark:bg-slate-900 rounded-[22px] flex items-center justify-center">
                  <ConnectXLogo size="lg" variant="gradient" static />
                </div>
              </div>

              <div>
                <h3 className="text-lg font-bold tracking-tight">ConnectX</h3>
                <p className="text-xs text-violet-600 dark:text-violet-400 font-semibold mt-0.5">
                  Secure Messaging Platform
                </p>
                <p className="text-xs text-slate-400 dark:text-slate-500 mt-2 max-w-xs mx-auto leading-relaxed">
                  End-to-end encrypted direct and group communications with complete cryptographic privacy.
                </p>
              </div>

              <div className="p-3.5 rounded-2xl bg-slate-50 dark:bg-slate-800/40 border border-slate-100 dark:border-slate-800/60 max-w-xs mx-auto space-y-1 text-xs">
                <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                  <span>Version</span>
                  <span className="font-mono font-bold text-slate-800 dark:text-slate-200">1.0.0</span>
                </div>
                <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                  <span>Encryption</span>
                  <span className="font-semibold text-emerald-600 dark:text-emerald-400">Active</span>
                </div>
              </div>
            </div>
          )}

          {/* VIEW: MAIN SETTINGS */}
          {view === 'main' && (
            <>
              {/* PRIVACY SECTION */}
              <div className="space-y-2">
                <SettingsSectionLabel>Privacy</SettingsSectionLabel>

                <div className="space-y-2">
                  {/* Profile photo visibility */}
                  <div className="p-3.5 bg-slate-50/70 dark:bg-slate-800/40 border border-slate-100 dark:border-slate-800/60 rounded-2xl space-y-2.5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <div className="p-2 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400 flex-shrink-0">
                          <Eye className="w-4 h-4" />
                        </div>
                        <div>
                          <p className="text-sm font-medium text-slate-800 dark:text-slate-200">
                            Profile photo visibility
                          </p>
                          <p className="text-[11px] text-slate-400">Choose who can view your picture</p>
                        </div>
                      </div>
                      {savingVisibility && <Loader2 className="w-3.5 h-3.5 animate-spin text-violet-500" />}
                    </div>

                    <div className="grid grid-cols-2 gap-2 pt-1">
                      {VISIBILITY_OPTIONS.map((opt) => {
                        const active = visibility === opt.value;
                        return (
                          <button
                            key={opt.value}
                            type="button"
                            disabled={savingVisibility}
                            onClick={() => handleSelectVisibility(opt.value)}
                            className={`py-2 px-3 rounded-xl text-xs font-semibold text-center border transition-all ${
                              active
                                ? 'bg-violet-600 text-white border-violet-600 shadow-sm'
                                : 'bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-300 border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600'
                            }`}
                          >
                            {opt.label}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  {/* Groups Privacy Row */}
                  <button
                    type="button"
                    onClick={() => setView('privacy-groups')}
                    className="w-full px-3.5 py-3 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
                  >
                    <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                      <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 flex-shrink-0">
                        <Users className="w-4 h-4" />
                      </div>
                      <span>
                        Groups
                        <span className="block text-[11px] font-normal text-slate-400">
                          Who can add me to groups?
                        </span>
                      </span>
                    </span>
                    <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400 font-medium">
                      <span>{currentGroupPrivacyLabel}</span>
                      <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-200 transition-colors" />
                    </div>
                  </button>
                </div>
              </div>

              {/* NOTIFICATIONS SECTION */}
              <div className="space-y-2">
                <SettingsSectionLabel>Notifications</SettingsSectionLabel>

                <div className="p-3.5 bg-slate-50/70 dark:bg-slate-800/40 border border-slate-100 dark:border-slate-800/60 rounded-2xl flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="p-2 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400 flex-shrink-0">
                      <Bell className="w-4 h-4" />
                    </div>
                    <div>
                      <p className="text-sm font-medium text-slate-800 dark:text-slate-200">
                        Message notifications
                      </p>
                      <p className="text-[11px] text-slate-400">Play sounds &amp; show alerts</p>
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={handleToggleNotificationsClick}
                    role="switch"
                    aria-checked={isNotificationsOn}
                    className={`w-11 h-6 flex items-center rounded-full p-1 transition-colors duration-200 ease-in-out ${
                      isNotificationsOn ? 'bg-violet-600' : 'bg-slate-300 dark:bg-slate-700'
                    }`}
                  >
                    <div
                      className={`bg-white w-4 h-4 rounded-full shadow-md transform transition-transform duration-200 ease-in-out ${
                        isNotificationsOn ? 'translate-x-5' : 'translate-x-0'
                      }`}
                    />
                  </button>
                </div>
              </div>

              {/* CHATS SECTION */}
              <div className="space-y-2">
                <SettingsSectionLabel>Chats</SettingsSectionLabel>

                <div className="p-3.5 bg-slate-50/70 dark:bg-slate-800/40 border border-slate-100 dark:border-slate-800/60 rounded-2xl flex items-start gap-3">
                  <div className="p-2 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400 flex-shrink-0 mt-0.5">
                    <Palette className="w-4 h-4" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-slate-800 dark:text-slate-200">
                      Chat Wallpapers &amp; Themes
                    </p>
                    <p className="text-[11px] text-slate-400 mt-0.5 leading-relaxed">
                      Custom background themes and wallpapers can be set per chat via the conversation options menu.
                    </p>
                  </div>
                </div>
              </div>

              {/* ABOUT SECTION */}
              <div className="space-y-2">
                <SettingsSectionLabel>About</SettingsSectionLabel>

                <button
                  type="button"
                  onClick={() => setView('about')}
                  className="w-full px-3.5 py-3 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
                >
                  <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                    <div className="p-2 rounded-xl bg-slate-500/10 text-slate-600 dark:text-slate-400 flex-shrink-0">
                      <Info className="w-4 h-4" />
                    </div>
                    <span>
                      About ConnectX
                      <span className="block text-[11px] font-normal text-slate-400">App version &amp; encryption details</span>
                    </span>
                  </span>
                  <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-200 transition-colors" />
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

