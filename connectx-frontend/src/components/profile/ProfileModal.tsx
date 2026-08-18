import React, { useRef, useState, useEffect } from 'react';
import {
  X,
  User as UserIcon,
  Smartphone,
  Sun,
  Moon,
  LogOut,
  Lock,
  Camera,
  Trash2,
  Loader2,
  Edit2,
  Mail,
  Check,
  KeyRound,
  CheckCircle2,
  ShieldOff,
  Settings,
  ChevronRight,
  Shield,
} from 'lucide-react';
import { User } from '../../types';
import { userApi } from '../../api/userApi';
import { PROFILE_PHOTO_ACCEPT, validateProfilePhotoFile, resolveProfileImageUrl } from '../../utils/profileImage';
import { ConnectXLogo } from '../common/ConnectXLogo';
import { usePWAInstall } from '../../utils/usePWAInstall';
import { ImageViewerModal } from '../common/ImageViewerModal';
import { saveImageUrlToGallery } from '../../utils/saveMedia';
import { SettingsSectionLabel } from '../common/SettingsPrimitives';

interface ProfileModalProps {
  currentUser: User;
  isDarkMode: boolean;
  onToggleTheme: () => void;
  onOpenDevices: () => void;
  onOpenBlockedUsers: () => void;
  onOpenSettings: () => void;
  onClose: () => void;
  onLogout: () => void;
  onUserUpdated: (user: User) => void;
}

/**
 * Install ConnectX — smart row for the Profile panel.
 * Handles all PWA states cleanly with no console errors.
 */
const InstallConnectXRow: React.FC = () => {
  const { state, install } = usePWAInstall();
  const [showIosInstructions, setShowIosInstructions] = useState(false);

  if (state === 'checking' || state === 'unavailable') return null;

  if (state === 'installed') {
    return (
      <div className="w-full px-3.5 py-2.5 flex items-center gap-3 rounded-2xl opacity-60 cursor-default select-none">
        <ConnectXLogo size="sm" variant="gradient" static className="flex-shrink-0" />
        <span className="flex-1 text-sm font-medium text-slate-700 dark:text-slate-200">ConnectX App</span>
        <span className="flex items-center gap-1 text-xs text-emerald-500 font-semibold">
          <CheckCircle2 className="w-3.5 h-3.5" />
          Installed
        </span>
      </div>
    );
  }

  if (state === 'ios') {
    return (
      <>
        <button
          onClick={() => setShowIosInstructions(!showIosInstructions)}
          className="w-full px-3.5 py-2.5 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
        >
          <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
            <ConnectXLogo size="sm" variant="gradient" static className="flex-shrink-0" />
            Install ConnectX
          </span>
          <span className="text-xs text-indigo-500 font-semibold">Instructions</span>
        </button>
        {showIosInstructions && (
          <div className="mx-1 mb-1 p-3 rounded-2xl bg-indigo-500/5 border border-indigo-500/15 space-y-1.5 animate-fadeIn">
            <p className="text-xs font-semibold text-slate-800 dark:text-slate-200">Add to Home Screen</p>
            <ol className="text-xs text-slate-500 dark:text-slate-400 space-y-1 list-none leading-relaxed">
              <li>1. Tap the <strong className="text-slate-700 dark:text-slate-300">Share</strong> icon in Safari</li>
              <li>2. Scroll and select <strong className="text-slate-700 dark:text-slate-300">Add to Home Screen</strong></li>
              <li>3. Tap <strong className="text-slate-700 dark:text-slate-300">Add</strong> to complete</li>
            </ol>
          </div>
        )}
      </>
    );
  }

  return (
    <button
      onClick={() => install()}
      className="w-full px-3.5 py-2.5 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
    >
      <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
        <ConnectXLogo size="sm" variant="gradient" static className="flex-shrink-0" />
        Install ConnectX
      </span>
      <span className="text-xs text-indigo-500 font-semibold group-hover:text-indigo-400">Install</span>
    </button>
  );
};

export const ProfileModal: React.FC<ProfileModalProps> = ({
  currentUser,
  isDarkMode,
  onToggleTheme,
  onOpenDevices,
  onOpenBlockedUsers,
  onOpenSettings,
  onClose,
  onLogout,
  onUserUpdated,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [previewUser, setPreviewUser] = useState<User>(currentUser);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [localPreviewUrl, setLocalPreviewUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [avatarViewerOpen, setAvatarViewerOpen] = useState(false);
  const [savingAvatarPhoto, setSavingAvatarPhoto] = useState(false);

  // Profile Edit State
  const [isEditing, setIsEditing] = useState(false);
  const [editUsername, setEditUsername] = useState(currentUser.username);
  const [editDisplayName, setEditDisplayName] = useState(currentUser.displayName || currentUser.username);

  // Email Change OTP State
  const [showEmailModal, setShowEmailModal] = useState(false);
  const [newEmail, setNewEmail] = useState('');
  const [otpCode, setOtpCode] = useState('');
  const [emailStep, setEmailStep] = useState<'ENTER_EMAIL' | 'ENTER_OTP'>('ENTER_EMAIL');
  const [emailSending, setEmailSending] = useState(false);
  const [emailError, setEmailError] = useState<string | null>(null);

  useEffect(() => {
    setPreviewUser(currentUser);
    setEditUsername(currentUser.username);
    setEditDisplayName(currentUser.displayName || currentUser.username);
    setSelectedFile(null);
    setLocalPreviewUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return null;
    });
    setError(null);
    setSuccessMsg(null);
  }, [currentUser]);

  const avatarImageUrl = localPreviewUrl || resolveProfileImageUrl(previewUser.profileImageUrl);
  const avatarInitial = (previewUser.displayName || previewUser.username || '?').charAt(0).toUpperCase();

  const handleSaveAvatarPhoto = async () => {
    if (!avatarImageUrl || savingAvatarPhoto) return;
    setSavingAvatarPhoto(true);
    try {
      await saveImageUrlToGallery(avatarImageUrl, `connectx-${previewUser.username || 'profile'}`);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to save photo';
      alert(message);
    } finally {
      setSavingAvatarPhoto(false);
    }
  };

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
      setSuccessMsg('Profile photo updated');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to upload profile photo';
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
      setSuccessMsg('Profile photo removed');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to remove profile photo';
      setError(message);
    } finally {
      setSaving(false);
    }
  };

  const handleSaveProfileDetails = async () => {
    if (saving) return;
    setSaving(true);
    setError(null);
    setSuccessMsg(null);

    try {
      const updatedUser = await userApi.updateProfile({
        username: editUsername.trim(),
        displayName: editDisplayName.trim(),
      });

      setPreviewUser(updatedUser);
      onUserUpdated(updatedUser);
      setIsEditing(false);
      setSuccessMsg('Profile updated successfully');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to update profile';
      setError(message);
    } finally {
      setSaving(false);
    }
  };

  const handleRequestEmailOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newEmail.trim() || emailSending) return;
    setEmailSending(true);
    setEmailError(null);

    try {
      await userApi.requestEmailChangeOtp(newEmail.trim());
      setEmailStep('ENTER_OTP');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to send OTP';
      setEmailError(message);
    } finally {
      setEmailSending(false);
    }
  };

  const handleVerifyEmailOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!otpCode.trim() || emailSending) return;
    setEmailSending(true);
    setEmailError(null);

    try {
      const updatedUser = await userApi.verifyEmailChangeOtp(newEmail.trim(), otpCode.trim());
      setPreviewUser(updatedUser);
      onUserUpdated(updatedUser);
      setShowEmailModal(false);
      setNewEmail('');
      setOtpCode('');
      setEmailStep('ENTER_EMAIL');
      setSuccessMsg('Email updated successfully');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Invalid verification code';
      setEmailError(message);
    } finally {
      setEmailSending(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center sm:justify-end p-3 sm:p-4 bg-slate-950/60 backdrop-blur-sm select-none animate-fadeIn"
      onClick={onClose}
    >
      <div
        className="w-full max-w-sm bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl overflow-hidden animate-pop-in mt-14 sm:mt-16 sm:mr-4 max-h-[88vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Hub Header */}
        <div className="p-5 border-b border-slate-100 dark:border-slate-800/80 bg-slate-50/50 dark:bg-slate-900/50 flex-shrink-0">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-3.5">
              <div className="relative group">
                <div
                  onClick={avatarImageUrl ? () => setAvatarViewerOpen(true) : undefined}
                  role={avatarImageUrl ? 'button' : undefined}
                  tabIndex={avatarImageUrl ? 0 : undefined}
                  onKeyDown={
                    avatarImageUrl
                      ? (e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            setAvatarViewerOpen(true);
                          }
                        }
                      : undefined
                  }
                  aria-label={avatarImageUrl ? 'View profile photo' : undefined}
                  title={avatarImageUrl ? 'Click to view photo' : undefined}
                  className={`w-16 h-16 rounded-2xl overflow-hidden bg-gradient-to-tr from-violet-600 to-indigo-500 flex items-center justify-center text-white text-xl font-bold flex-shrink-0 shadow-lg shadow-indigo-500/10 ${
                    avatarImageUrl ? 'cursor-pointer hover:opacity-95 transition-opacity' : ''
                  }`}
                >
                  {avatarImageUrl ? (
                    <img
                      src={avatarImageUrl}
                      alt={previewUser.displayName || previewUser.username}
                      className="w-full h-full object-cover pointer-events-none"
                    />
                  ) : (
                    <span>{avatarInitial}</span>
                  )}
                </div>
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="absolute -bottom-1 -right-1 w-7 h-7 rounded-xl bg-violet-600 hover:bg-violet-500 text-white flex items-center justify-center shadow-md transition-transform hover:scale-105 active:scale-95"
                  aria-label="Change profile photo"
                  title="Upload profile photo"
                >
                  <Camera className="w-3.5 h-3.5" />
                </button>
              </div>

              <div className="min-w-0 flex-1">
                <h3 className="font-bold text-slate-900 dark:text-white truncate text-base tracking-tight">
                  {previewUser.displayName || previewUser.username}
                </h3>
                <p className="text-xs font-semibold text-violet-600 dark:text-violet-400 truncate mt-0.5">
                  @{previewUser.username}
                </p>
                {previewUser.email && (
                  <p className="text-[11px] text-slate-400 dark:text-slate-500 truncate mt-0.5">{previewUser.email}</p>
                )}
              </div>
            </div>

            <button
              onClick={onClose}
              className="p-1.5 -mr-1 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              aria-label="Close"
            >
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

          {/* Photo Actions Bar */}
          {(selectedFile || previewUser.profileImageUrl) && (
            <div className="mt-3.5 pt-3 border-t border-slate-200/60 dark:border-slate-800 flex items-center gap-2">
              {selectedFile && (
                <button
                  type="button"
                  onClick={handleSavePhoto}
                  disabled={saving}
                  className="flex-1 py-1.5 px-3 rounded-xl bg-violet-600 hover:bg-violet-500 disabled:opacity-60 text-white text-xs font-semibold inline-flex items-center justify-center gap-1.5 transition-colors shadow-sm"
                >
                  {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                  Save Photo
                </button>
              )}
              {previewUser.profileImageUrl && !selectedFile && (
                <button
                  type="button"
                  onClick={handleRemovePhoto}
                  disabled={saving}
                  className="px-3 py-1.5 rounded-xl text-xs font-semibold text-rose-500 hover:bg-rose-500/10 inline-flex items-center gap-1.5 transition-colors"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  Remove Photo
                </button>
              )}
            </div>
          )}

          {error && <p className="mt-2 text-xs text-rose-500 font-medium">{error}</p>}
          {successMsg && <p className="mt-2 text-xs text-emerald-500 font-medium">{successMsg}</p>}
        </div>

        {/* Hub Body */}
        <div className="flex-1 overflow-y-auto p-4 space-y-5">
          {/* SECTION 1: PROFILE */}
          <div className="space-y-2">
            <SettingsSectionLabel>Profile</SettingsSectionLabel>

            {isEditing ? (
              <div className="p-3.5 bg-slate-50 dark:bg-slate-800/60 rounded-2xl space-y-3 border border-slate-200 dark:border-slate-700/60 animate-fadeIn">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-bold text-slate-800 dark:text-slate-200">Edit Details</span>
                  <button
                    type="button"
                    onClick={() => setIsEditing(false)}
                    className="text-xs font-medium text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                  >
                    Cancel
                  </button>
                </div>

                <div>
                  <label className="block text-[11px] font-semibold text-slate-500 dark:text-slate-400 mb-1">
                    Display Name
                  </label>
                  <input
                    type="text"
                    value={editDisplayName}
                    onChange={(e) => setEditDisplayName(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl text-xs bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 text-slate-900 dark:text-white outline-none focus:border-violet-500 transition-colors select-text"
                    placeholder="Display name"
                  />
                </div>

                <div>
                  <label className="block text-[11px] font-semibold text-slate-500 dark:text-slate-400 mb-1">
                    Username
                  </label>
                  <input
                    type="text"
                    value={editUsername}
                    onChange={(e) => setEditUsername(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl text-xs bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 text-slate-900 dark:text-white outline-none focus:border-violet-500 transition-colors select-text"
                    placeholder="Username"
                  />
                </div>

                <button
                  type="button"
                  onClick={handleSaveProfileDetails}
                  disabled={saving}
                  className="w-full py-2 rounded-xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-1.5 disabled:opacity-60 transition-colors shadow-sm"
                >
                  {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                  Save Changes
                </button>
              </div>
            ) : (
              <div className="p-3 bg-slate-50/70 dark:bg-slate-800/40 border border-slate-100 dark:border-slate-800/60 rounded-2xl space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="p-2 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400 flex-shrink-0">
                      <UserIcon className="w-4 h-4" />
                    </div>
                    <div className="min-w-0">
                      <p className="text-xs font-bold text-slate-800 dark:text-slate-100 truncate">
                        {previewUser.displayName || 'No display name set'}
                      </p>
                      <p className="text-[11px] text-slate-400 font-medium truncate">@{previewUser.username}</p>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setIsEditing(true)}
                    className="px-2.5 py-1.5 rounded-xl hover:bg-violet-500/10 text-violet-600 dark:text-violet-400 text-xs font-semibold flex items-center gap-1 transition-colors"
                  >
                    <Edit2 className="w-3 h-3" />
                    Edit
                  </button>
                </div>

                {/* Email row */}
                <div className="pt-2 border-t border-slate-200/50 dark:border-slate-800/50 flex items-center justify-between">
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="p-2 rounded-xl bg-pink-500/10 text-pink-500 flex-shrink-0">
                      <Mail className="w-4 h-4" />
                    </div>
                    <div className="min-w-0">
                      <p className="text-[11px] font-semibold text-slate-400">Email Address</p>
                      <p className="text-xs font-medium text-slate-700 dark:text-slate-200 truncate">
                        {previewUser.email || 'None'}
                      </p>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setShowEmailModal(true);
                      setEmailStep('ENTER_EMAIL');
                      setEmailError(null);
                    }}
                    className="px-2.5 py-1.5 rounded-xl hover:bg-pink-500/10 text-pink-600 dark:text-pink-400 text-xs font-semibold transition-colors"
                  >
                    Change
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* SECTION 2: ACCOUNT */}
          <div className="space-y-1">
            <SettingsSectionLabel>Account</SettingsSectionLabel>

            <button
              onClick={() => {
                onClose();
                onOpenDevices();
              }}
              className="w-full px-3.5 py-3 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
            >
              <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                <div className="p-2 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400 flex-shrink-0">
                  <Smartphone className="w-4 h-4" />
                </div>
                <span>
                  Devices
                  <span className="block text-[11px] font-normal text-slate-400">Manage registered active devices</span>
                </span>
              </span>
              <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-200 transition-colors" />
            </button>

            <button
              onClick={() => {
                onClose();
                onOpenBlockedUsers();
              }}
              className="w-full px-3.5 py-3 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
            >
              <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                <div className="p-2 rounded-xl bg-rose-500/10 text-rose-500 flex-shrink-0">
                  <ShieldOff className="w-4 h-4" />
                </div>
                <span>
                  Blocked Users
                  <span className="block text-[11px] font-normal text-slate-400">Manage contacts you've blocked</span>
                </span>
              </span>
              <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-200 transition-colors" />
            </button>
          </div>

          {/* SECTION 3: PREFERENCES */}
          <div className="space-y-1">
            <SettingsSectionLabel>Preferences</SettingsSectionLabel>

            <button
              onClick={() => {
                onClose();
                onOpenSettings();
              }}
              className="w-full px-3.5 py-3 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left group"
            >
              <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 flex-shrink-0">
                  <Settings className="w-4 h-4" />
                </div>
                <span>
                  Settings
                  <span className="block text-[11px] font-normal text-slate-400">Privacy, notifications & chat settings</span>
                </span>
              </span>
              <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-200 transition-colors" />
            </button>

            <button
              onClick={onToggleTheme}
              className="w-full px-3.5 py-3 flex items-center justify-between hover:bg-slate-100/80 dark:hover:bg-slate-800/60 transition-colors rounded-2xl text-left"
            >
              <span className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                <div className="p-2 rounded-xl bg-amber-500/10 text-amber-500 flex-shrink-0">
                  {isDarkMode ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                </div>
                <span>
                  Appearance
                  <span className="block text-[11px] font-normal text-slate-400">Theme mode</span>
                </span>
              </span>
              <span className="text-xs px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-200 font-semibold">
                {isDarkMode ? 'Dark' : 'Light'}
              </span>
            </button>

            <InstallConnectXRow />
          </div>

          {/* SECURITY BADGE */}
          <div className="px-3.5 py-2.5 rounded-2xl bg-violet-500/5 border border-violet-500/15 flex items-center gap-2.5 text-xs text-slate-500 dark:text-slate-400">
            <Lock className="w-4 h-4 text-violet-500 flex-shrink-0" />
            <span>End-to-end encrypted with device keys.</span>
          </div>

          {/* SECTION 4: ACCOUNT ACTION */}
          <div className="space-y-1 pt-1">
            <SettingsSectionLabel>Account Action</SettingsSectionLabel>
            <button
              onClick={onLogout}
              className="w-full px-3.5 py-3 rounded-2xl text-sm font-semibold text-rose-500 hover:bg-rose-500/10 flex items-center gap-3 transition-colors text-left"
            >
              <div className="p-2 rounded-xl bg-rose-500/10 text-rose-500 flex-shrink-0">
                <LogOut className="w-4 h-4" />
              </div>
              <span>Log out of ConnectX</span>
            </button>
          </div>
        </div>
      </div>

      {avatarImageUrl && (
        <ImageViewerModal
          open={avatarViewerOpen}
          imageUrl={avatarImageUrl}
          alt={previewUser.displayName || previewUser.username}
          title={previewUser.displayName || previewUser.username}
          onClose={() => setAvatarViewerOpen(false)}
          onSave={handleSaveAvatarPhoto}
          saving={savingAvatarPhoto}
        />
      )}

      {/* Email Change Modal */}
      {showEmailModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fadeIn"
          onClick={() => setShowEmailModal(false)}
        >
          <div
            className="w-full max-w-sm bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl p-6 shadow-2xl space-y-4 text-slate-900 dark:text-white animate-pop-in"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="p-2 rounded-xl bg-pink-500/10 text-pink-500">
                  <Mail className="w-5 h-5" />
                </div>
                <h4 className="text-base font-bold">Change Email</h4>
              </div>
              <button
                onClick={() => setShowEmailModal(false)}
                className="p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-xl"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {emailStep === 'ENTER_EMAIL' ? (
              <form onSubmit={handleRequestEmailOtp} className="space-y-4">
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
                  Enter your new email address. We will send a 6-digit verification code to confirm ownership.
                </p>
                <input
                  type="email"
                  required
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  placeholder="newemail@example.com"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 text-sm text-slate-900 dark:text-white outline-none focus:border-pink-500 transition-colors select-text"
                />
                {emailError && <p className="text-xs text-rose-500 font-medium">{emailError}</p>}
                <button
                  type="submit"
                  disabled={emailSending}
                  className="w-full py-2.5 rounded-xl bg-pink-600 hover:bg-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 disabled:opacity-50 transition-colors shadow-sm"
                >
                  {emailSending && <Loader2 className="w-4 h-4 animate-spin" />}
                  Send Verification Code
                </button>
              </form>
            ) : (
              <form onSubmit={handleVerifyEmailOtp} className="space-y-4">
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
                  We sent a 6-digit code to <strong className="text-slate-700 dark:text-slate-200">{newEmail}</strong>. Enter it below to complete email change.
                </p>
                <div className="relative">
                  <input
                    type="text"
                    required
                    maxLength={6}
                    value={otpCode}
                    onChange={(e) => setOtpCode(e.target.value)}
                    placeholder="123456"
                    className="w-full px-3.5 py-2.5 tracking-widest text-center text-lg font-mono rounded-xl bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 text-slate-900 dark:text-white outline-none focus:border-pink-500 transition-colors select-text"
                  />
                  <KeyRound className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
                </div>
                {emailError && <p className="text-xs text-rose-500 font-medium">{emailError}</p>}
                <div className="flex items-center gap-2 pt-1">
                  <button
                    type="button"
                    onClick={() => setEmailStep('ENTER_EMAIL')}
                    className="px-4 py-2.5 rounded-xl text-xs font-semibold text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                  >
                    Back
                  </button>
                  <button
                    type="submit"
                    disabled={emailSending}
                    className="flex-1 py-2.5 rounded-xl bg-pink-600 hover:bg-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 disabled:opacity-50 transition-colors shadow-sm"
                  >
                    {emailSending && <Loader2 className="w-4 h-4 animate-spin" />}
                    Verify &amp; Save
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

