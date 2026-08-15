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
} from 'lucide-react';
import { User } from '../../types';
import { userApi } from '../../api/userApi';
import { PROFILE_PHOTO_ACCEPT, validateProfilePhotoFile, resolveProfileImageUrl } from '../../utils/profileImage';
import { ConnectXLogo } from '../common/ConnectXLogo';
import { usePWAInstall } from '../../utils/usePWAInstall';
import { ImageViewerModal } from '../common/ImageViewerModal';
import { saveImageUrlToGallery } from '../../utils/saveMedia';


interface ProfileModalProps {
  currentUser: User;
  isDarkMode: boolean;
  onToggleTheme: () => void;
  onOpenDevices: () => void;
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

  // Hide entirely for unavailable/checking/installed states
  if (state === 'checking' || state === 'unavailable') return null;

  if (state === 'installed') {
    return (
      <div className="w-full px-3 py-2.5 flex items-center gap-3 rounded-xl opacity-60 cursor-default select-none">
        <ConnectXLogo size="sm" variant="gradient" static className="flex-shrink-0" />
        <span className="flex-1 text-xs font-medium text-slate-700 dark:text-slate-200">ConnectX</span>
        <span className="flex items-center gap-1 text-[11px] text-emerald-500 font-medium">
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
          className="w-full px-3 py-2.5 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors rounded-xl text-left"
        >
          <span className="flex items-center gap-3 text-xs font-medium text-slate-700 dark:text-slate-200">
            <ConnectXLogo size="sm" variant="gradient" static className="flex-shrink-0" />
            Install ConnectX
          </span>
          <span className="text-[11px] text-indigo-500 font-medium">How?</span>
        </button>
        {showIosInstructions && (
          <div className="mx-1 mb-1 px-3 py-2.5 rounded-xl bg-indigo-500/5 border border-indigo-500/15 space-y-1">
            <p className="text-[11px] font-semibold text-slate-700 dark:text-slate-200">Add to Home Screen</p>
            <ol className="text-[11px] text-slate-500 dark:text-slate-400 space-y-1 list-none">
              <li>1. Tap the <strong className="text-slate-600 dark:text-slate-300">Share</strong> button in Safari</li>
              <li>2. Scroll down and tap <strong className="text-slate-600 dark:text-slate-300">Add to Home Screen</strong></li>
              <li>3. Tap <strong className="text-slate-600 dark:text-slate-300">Add</strong> to confirm</li>
            </ol>
          </div>
        )}
      </>
    );
  }

  // state === 'available' — Chrome/Edge native prompt
  return (
    <button
      onClick={() => install()}
      className="w-full px-3 py-2.5 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors rounded-xl text-left group"
    >
      <span className="flex items-center gap-3 text-xs font-medium text-slate-700 dark:text-slate-200">
        <ConnectXLogo size="sm" variant="gradient" static className="flex-shrink-0" />
        Install ConnectX
      </span>
      <span className="text-[11px] text-indigo-500 font-medium group-hover:text-indigo-400">Install</span>
    </button>
  );
};

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
    <div className="fixed inset-0 z-50 flex items-start justify-end p-3 sm:p-4 bg-black/40 backdrop-blur-[2px] select-none" onClick={onClose}>
      <div
        className="w-full max-w-sm bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-2xl shadow-2xl overflow-hidden animate-pop-in mt-12 sm:mt-14 mr-0 sm:mr-2"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-slate-200 dark:border-slate-800">
          <div className="flex items-start gap-3">
            <div className="relative">
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
                title={avatarImageUrl ? 'View profile photo' : undefined}
                className={`w-16 h-16 rounded-full overflow-hidden bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white text-xl font-semibold flex-shrink-0 shadow-md ${
                  avatarImageUrl ? 'cursor-pointer hover:opacity-90 transition-opacity' : ''
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
                className="absolute -bottom-1 -right-1 w-7 h-7 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white flex items-center justify-center shadow-md"
                aria-label="Change profile photo"
              >
                <Camera className="w-3.5 h-3.5" />
              </button>
            </div>

            <div className="flex-1 min-w-0 pt-0.5">
              <h3 className="font-semibold text-slate-900 dark:text-white truncate text-base">
                {previewUser.displayName || previewUser.username}
              </h3>
              <p className="text-xs text-indigo-500 dark:text-indigo-400 truncate">@{previewUser.username}</p>
              <p className="text-[11px] text-slate-400 truncate mt-0.5">{previewUser.email}</p>
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
                className="flex-1 py-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 text-white text-xs font-semibold inline-flex items-center justify-center gap-1.5"
              >
                {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
                Save Photo
              </button>
            )}
            {(previewUser.profileImageUrl || selectedFile) && (
              <button
                type="button"
                onClick={handleRemovePhoto}
                disabled={saving}
                className="px-3 py-1.5 rounded-xl text-xs font-medium text-rose-500 hover:bg-rose-500/10 inline-flex items-center gap-1"
              >
                <Trash2 className="w-3.5 h-3.5" />
                Remove
              </button>
            )}
          </div>

          {error && <p className="mt-2 text-xs text-rose-500 font-medium">{error}</p>}
          {successMsg && <p className="mt-2 text-xs text-emerald-500 font-medium">{successMsg}</p>}
        </div>

        <div className="py-2 px-3 space-y-1">
          {/* Profile Details Edit Section */}
          {isEditing ? (
            <div className="p-3 bg-slate-50 dark:bg-slate-800/60 rounded-xl space-y-3 border border-slate-200 dark:border-slate-700/60">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-700 dark:text-slate-200">Edit Information</span>
                <button
                  type="button"
                  onClick={() => setIsEditing(false)}
                  className="text-xs text-slate-400 hover:text-slate-200"
                >
                  Cancel
                </button>
              </div>

              <div>
                <label className="block text-[11px] font-medium text-slate-500 dark:text-slate-400 mb-1">Username</label>
                <input
                  type="text"
                  value={editUsername}
                  onChange={(e) => setEditUsername(e.target.value)}
                  className="w-full px-3 py-1.5 rounded-lg text-xs bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 text-slate-900 dark:text-white outline-none focus:border-indigo-500 select-text"
                  placeholder="Username"
                />
              </div>

              <div>
                <label className="block text-[11px] font-medium text-slate-500 dark:text-slate-400 mb-1">Display Name</label>
                <input
                  type="text"
                  value={editDisplayName}
                  onChange={(e) => setEditDisplayName(e.target.value)}
                  className="w-full px-3 py-1.5 rounded-lg text-xs bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 text-slate-900 dark:text-white outline-none focus:border-indigo-500 select-text"
                  placeholder="Display name"
                />
              </div>

              <button
                type="button"
                onClick={handleSaveProfileDetails}
                disabled={saving}
                className="w-full py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold flex items-center justify-center gap-1.5 disabled:opacity-60"
              >
                {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                Save Changes
              </button>
            </div>
          ) : (
            <div className="flex items-center justify-between p-3 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors">
              <div className="flex items-center gap-3 min-w-0">
                <UserIcon className="w-4 h-4 text-indigo-400 flex-shrink-0" />
                <div className="min-w-0">
                  <p className="text-xs font-medium text-slate-700 dark:text-slate-200">@{previewUser.username}</p>
                  <p className="text-[11px] text-slate-400 truncate">{previewUser.displayName || 'No display name'}</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsEditing(true)}
                className="p-1.5 rounded-lg hover:bg-indigo-500/10 text-indigo-500 text-xs font-medium flex items-center gap-1"
              >
                <Edit2 className="w-3.5 h-3.5" />
                Edit
              </button>
            </div>
          )}

          {/* Email Edit Row */}
          <div className="flex items-center justify-between p-3 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors">
            <div className="flex items-center gap-3 min-w-0">
              <Mail className="w-4 h-4 text-pink-400 flex-shrink-0" />
              <div className="min-w-0">
                <p className="text-xs font-medium text-slate-700 dark:text-slate-200">Email Address</p>
                <p className="text-[11px] text-slate-400 truncate">{previewUser.email}</p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => {
                setShowEmailModal(true);
                setEmailStep('ENTER_EMAIL');
                setEmailError(null);
              }}
              className="p-1.5 rounded-lg hover:bg-pink-500/10 text-pink-500 text-xs font-medium flex items-center gap-1"
            >
              Change
            </button>
          </div>

          <button
            onClick={() => {
              onClose();
              onOpenDevices();
            }}
            className="w-full px-3 py-2.5 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors rounded-xl text-left"
          >
            <span className="flex items-center gap-3 text-xs font-medium text-slate-700 dark:text-slate-200">
              <Smartphone className="w-4 h-4 text-violet-400" />
              Devices
            </span>
            <span className="text-[11px] text-indigo-500 font-medium">Manage</span>
          </button>

          <InstallConnectXRow />

          <button
            onClick={onToggleTheme}
            className="w-full px-3 py-2.5 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors rounded-xl text-left"
          >
            <span className="flex items-center gap-3 text-xs font-medium text-slate-700 dark:text-slate-200">
              {isDarkMode ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-indigo-400" />}
              Appearance
            </span>
            <span className="text-[11px] px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-300 font-medium">
              {isDarkMode ? 'Dark' : 'Light'}
            </span>
          </button>

          <div className="mx-1 my-2 px-3 py-2 rounded-xl bg-indigo-500/5 border border-indigo-500/10 text-[11px] text-slate-500 dark:text-slate-400 flex items-start gap-2">
            <Lock className="w-3.5 h-3.5 text-indigo-400 flex-shrink-0 mt-0.5" />
            <span>End-to-end encryption keys stored locally in IndexedDB.</span>
          </div>
        </div>

        <div className="px-3 pb-3 border-t border-slate-200 dark:border-slate-800 pt-2">
          <button
            onClick={onLogout}
            className="w-full py-2 rounded-xl text-xs font-semibold text-rose-500 hover:bg-rose-500/10 flex items-center justify-center gap-2 transition-colors"
          >
            <LogOut className="w-3.5 h-3.5" />
            Log out
          </button>
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
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" onClick={() => setShowEmailModal(false)}>
          <div className="w-full max-w-sm bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h4 className="text-sm font-semibold text-white flex items-center gap-2">
                <Mail className="w-4 h-4 text-pink-400" />
                Change Email Address
              </h4>
              <button onClick={() => setShowEmailModal(false)} className="text-slate-400 hover:text-white">
                <X className="w-4 h-4" />
              </button>
            </div>

            {emailStep === 'ENTER_EMAIL' ? (
              <form onSubmit={handleRequestEmailOtp} className="space-y-3">
                <p className="text-xs text-slate-400">
                  Enter your new email address. We will send a 6-digit verification code to confirm ownership.
                </p>
                <input
                  type="email"
                  required
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  placeholder="newemail@example.com"
                  className="w-full px-3 py-2 rounded-xl bg-slate-800 border border-slate-700 text-sm text-white outline-none focus:border-pink-500 select-text"
                />
                {emailError && <p className="text-xs text-rose-400">{emailError}</p>}
                <button
                  type="submit"
                  disabled={emailSending}
                  className="w-full py-2.5 rounded-xl bg-pink-600 hover:bg-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 disabled:opacity-50"
                >
                  {emailSending ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                  Send Verification Code
                </button>
              </form>
            ) : (
              <form onSubmit={handleVerifyEmailOtp} className="space-y-3">
                <p className="text-xs text-slate-400">
                  We sent a 6-digit code to <strong className="text-slate-200">{newEmail}</strong>. Enter it below to complete email change.
                </p>
                <div className="relative">
                  <input
                    type="text"
                    required
                    maxLength={6}
                    value={otpCode}
                    onChange={(e) => setOtpCode(e.target.value)}
                    placeholder="123456"
                    className="w-full px-3 py-2 tracking-widest text-center text-lg font-mono rounded-xl bg-slate-800 border border-slate-700 text-white outline-none focus:border-pink-500 select-text"
                  />
                  <KeyRound className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
                </div>
                {emailError && <p className="text-xs text-rose-400">{emailError}</p>}
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setEmailStep('ENTER_EMAIL')}
                    className="px-3 py-2 rounded-xl text-xs text-slate-400 hover:bg-slate-800"
                  >
                    Back
                  </button>
                  <button
                    type="submit"
                    disabled={emailSending}
                    className="flex-1 py-2.5 rounded-xl bg-pink-600 hover:bg-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 disabled:opacity-50"
                  >
                    {emailSending ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                    Verify & Save Email
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
