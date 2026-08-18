import React, { useState } from 'react';
import { Settings, X, Eye, Download, Loader2, Check, ChevronDown } from 'lucide-react';
import { User, ProfilePhotoVisibility } from '../../types';
import { userApi } from '../../api/userApi';

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
}

const VISIBILITY_OPTIONS: { value: ProfilePhotoVisibility; label: string; description: string }[] = [
  { value: 'EVERYONE', label: 'Everyone', description: 'Any ConnectX user can see your profile photo' },
  { value: 'CONNECTIONS', label: 'Connections', description: 'Only people you\'re connected with can see it' },
];

export const SettingsModal: React.FC<SettingsModalProps> = ({
  currentUser,
  onClose,
  onUserUpdated,
  onExportChat,
  exportingChat,
  activeDirectChatName,
}) => {
  const [visibility, setVisibility] = useState<ProfilePhotoVisibility>(currentUser.profilePhotoVisibility || 'EVERYONE');
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const handleSelectVisibility = async (next: ProfilePhotoVisibility) => {
    setDropdownOpen(false);
    if (next === visibility || saving) return;

    const previous = visibility;
    setVisibility(next);
    setSaving(true);
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
      setSaving(false);
    }
  };

  const selectedOption = VISIBILITY_OPTIONS.find((o) => o.value === visibility) ?? VISIBILITY_OPTIONS[0];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-500">
              <Settings className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Settings</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Privacy and chat preferences</p>
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

        <div className="space-y-2">
          <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">Privacy</p>
          <div className="p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl space-y-2.5">
            <div className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
              <Eye className="w-4 h-4 text-indigo-400 flex-shrink-0" />
              Profile Photo Visibility
            </div>

            <div className="relative">
              <button
                type="button"
                onClick={() => setDropdownOpen((v) => !v)}
                disabled={saving}
                className="w-full flex items-center justify-between px-3 py-2.5 bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 rounded-xl text-sm text-left disabled:opacity-60"
              >
                <span className="flex items-center gap-2">
                  {saving && <Loader2 className="w-3.5 h-3.5 animate-spin text-indigo-500" />}
                  {selectedOption.label}
                </span>
                <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform ${dropdownOpen ? 'rotate-180' : ''}`} />
              </button>

              {dropdownOpen && (
                <>
                  <div className="fixed inset-0 z-10" onClick={() => setDropdownOpen(false)} />
                  <div className="absolute left-0 right-0 top-full mt-1 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-20 overflow-hidden">
                    {VISIBILITY_OPTIONS.map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => handleSelectVisibility(option.value)}
                        className="w-full text-left px-3 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800 flex items-start gap-2"
                      >
                        <div className="flex-1 min-w-0">
                          <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{option.label}</p>
                          <p className="text-[11px] text-slate-400">{option.description}</p>
                        </div>
                        {option.value === visibility && <Check className="w-4 h-4 text-indigo-500 flex-shrink-0 mt-0.5" />}
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>

            <p className="text-[11px] text-slate-400">{selectedOption.description}</p>
            {error && <p className="text-xs text-rose-500 font-medium">{error}</p>}
            {successMsg && <p className="text-xs text-emerald-500 font-medium">{successMsg}</p>}
          </div>
        </div>

        <div className="space-y-2">
          <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">Chat</p>
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
                Export Chat
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
      </div>
    </div>
  );
};
