import React, { useRef, useState, useEffect } from 'react';
import {
  X,
  User as UserIcon,
  Shield,
  Smartphone,
  Sun,
  Moon,
  LogOut,
  Lock,
  Settings,
  Camera,
  Trash2,
  Loader2,
} from 'lucide-react';
import { User } from '../../types';
import { userApi } from '../../api/userApi';
import { PROFILE_PHOTO_ACCEPT, validateProfilePhotoFile, resolveProfileImageUrl } from '../../utils/profileImage';

interface ProfileModalProps {
  currentUser: User;
  isDarkMode: boolean;
  onToggleTheme: () => void;
  onOpenDevices: () => void;
  onClose: () => void;
  onLogout: () => void;
  onUserUpdated: (user: User) => void;
}

export const ProfileModal: React.FC<ProfileModalProps> = ({
  currentUser,
  isDarkMode,
  onToggleTheme,
  onOpenDevices,
  onClose,
  onLogout,
  onUserUpdated,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [previewUser, setPreviewUser] = useState<User>(currentUser);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [localPreviewUrl, setLocalPreviewUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setPreviewUser(currentUser);
    setSelectedFile(null);
    setLocalPreviewUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return null;
    });
    setError(null);
  }, [currentUser]);

  const avatarImageUrl = localPreviewUrl || resolveProfileImageUrl(previewUser.profileImageUrl);
  const avatarInitial = (previewUser.displayName || previewUser.username || '?').charAt(0).toUpperCase();

  const handleSelectPhoto = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    const validationError = validateProfilePhotoFile(file);
    if (validationError) {
      setError(validationError);
      return;
    }

    setError(null);
    setSelectedFile(file);
    if (localPreviewUrl) {
      URL.revokeObjectURL(localPreviewUrl);
    }
    setLocalPreviewUrl(URL.createObjectURL(file));
  };

  const handleSavePhoto = async () => {
    if (!selectedFile || saving) return;

    setSaving(true);
    setError(null);
    try {
      const updatedUser = await userApi.uploadProfilePhoto(selectedFile);
      setPreviewUser(updatedUser);
      setSelectedFile(null);
      if (localPreviewUrl) {
        URL.revokeObjectURL(localPreviewUrl);
      }
      setLocalPreviewUrl(null);
      onUserUpdated(updatedUser);
    } catch (err: unknown) {
      const message =
        err instanceof Error
          ? err.message
          : typeof err === 'object' && err !== null && 'message' in err
            ? String((err as { message: unknown }).message)
            : 'Failed to upload profile photo';
      setError(message);
    } finally {
      setSaving(false);
    }
  };

  const handleRemovePhoto = async () => {
    if (saving) return;

    setSaving(true);
    setError(null);
    try {
      const updatedUser = await userApi.removeProfilePhoto();
      setPreviewUser(updatedUser);
      setSelectedFile(null);
      if (localPreviewUrl) {
        URL.revokeObjectURL(localPreviewUrl);
      }
      setLocalPreviewUrl(null);
      onUserUpdated(updatedUser);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to remove profile photo';
      setError(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-end p-3 sm:p-4 bg-black/40 backdrop-blur-[2px]" onClick={onClose}>
      <div
        className="w-full max-w-xs bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-2xl shadow-2xl overflow-hidden animate-pop-in mt-12 sm:mt-14 mr-0 sm:mr-2"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-4 border-b border-slate-200 dark:border-slate-800">
          <div className="flex items-start gap-3">
            <div className="relative">
              <div className="w-16 h-16 rounded-full overflow-hidden bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white text-xl font-semibold flex-shrink-0">
                {avatarImageUrl ? (
                  <img
                    src={avatarImageUrl}
                    alt={previewUser.displayName || previewUser.username}
                    className="w-full h-full object-cover"
                  />
                ) : (
                  <span>{avatarInitial}</span>
                )}
              </div>
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="absolute -bottom-1 -right-1 w-8 h-8 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white flex items-center justify-center shadow-md"
                aria-label="Change profile photo"
              >
                <Camera className="w-4 h-4" />
              </button>
            </div>
            <div className="flex-1 min-w-0 pt-1">
              <h3 className="font-semibold text-slate-900 dark:text-white truncate">
                {previewUser.displayName || previewUser.username}
              </h3>
              <p className="text-xs text-indigo-500 dark:text-indigo-400 truncate">@{previewUser.username}</p>
              <p className="text-[11px] text-slate-400 mt-1">JPG, PNG, WEBP up to 15 MB</p>
            </div>
            <button onClick={onClose} className="p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg" aria-label="Close">
              <X className="w-4 h-4" />
            </button>
          </div>

          <input
            ref={fileInputRef}
            type="file"
            accept={PROFILE_PHOTO_ACCEPT}
            className="hidden"
            onChange={handleSelectPhoto}
          />

          <div className="mt-3 flex items-center gap-2">
            {selectedFile && (
              <button
                type="button"
                onClick={handleSavePhoto}
                disabled={saving}
                className="flex-1 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 text-white text-sm font-semibold inline-flex items-center justify-center gap-2"
              >
                {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                Save Photo
              </button>
            )}
            {(previewUser.profileImageUrl || selectedFile) && (
              <button
                type="button"
                onClick={handleRemovePhoto}
                disabled={saving}
                className="px-3 py-2 rounded-xl text-sm font-medium text-rose-500 hover:bg-rose-500/10 inline-flex items-center gap-1.5"
              >
                <Trash2 className="w-4 h-4" />
                Remove
              </button>
            )}
          </div>

          {error && (
            <p className="mt-2 text-xs text-rose-500">{error}</p>
          )}
        </div>

        <div className="py-2">
          <MenuRow icon={<UserIcon className="w-4 h-4 text-indigo-400" />} label="My Profile" value={currentUser.email} />
          <MenuRow icon={<Shield className="w-4 h-4 text-pink-400" />} label="Security" value="E2EE active" />
          <button
            onClick={() => {
              onClose();
              onOpenDevices();
            }}
            className="w-full px-4 py-3 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors text-left"
          >
            <span className="flex items-center gap-3 text-sm text-slate-700 dark:text-slate-200">
              <Smartphone className="w-4 h-4 text-violet-400" />
              Devices
            </span>
            <span className="text-xs text-indigo-500">Manage</span>
          </button>
          <button
            onClick={onToggleTheme}
            className="w-full px-4 py-3 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors text-left"
          >
            <span className="flex items-center gap-3 text-sm text-slate-700 dark:text-slate-200">
              {isDarkMode ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-indigo-400" />}
              Appearance
            </span>
            <span className="text-xs px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-300">
              {isDarkMode ? 'Dark' : 'Light'}
            </span>
          </button>
          <MenuRow icon={<Settings className="w-4 h-4 text-slate-400" />} label="Settings" value="Preferences" />
          <div className="mx-4 my-2 px-3 py-2 rounded-xl bg-indigo-500/5 border border-indigo-500/10 text-[11px] text-slate-500 dark:text-slate-400 flex items-start gap-2">
            <Lock className="w-3.5 h-3.5 text-indigo-400 flex-shrink-0 mt-0.5" />
            <span>Private keys stored locally in IndexedDB. AES-256-GCM encryption.</span>
          </div>
        </div>

        <div className="px-3 pb-3 border-t border-slate-200 dark:border-slate-800 pt-2">
          <button
            onClick={onLogout}
            className="w-full py-2.5 rounded-xl text-sm font-semibold text-rose-500 hover:bg-rose-500/10 flex items-center justify-center gap-2 transition-colors"
          >
            <LogOut className="w-4 h-4" />
            Log out
          </button>
        </div>
      </div>
    </div>
  );
};

function MenuRow({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="px-4 py-3 flex items-center justify-between">
      <span className="flex items-center gap-3 text-sm text-slate-700 dark:text-slate-200">
        {icon}
        {label}
      </span>
      <span className="text-xs text-slate-400 truncate max-w-[120px]">{value}</span>
    </div>
  );
}
