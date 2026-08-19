import React, { useEffect, useRef, useState } from 'react';
import { Users, X, Loader2, Camera, Trash2 } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { groupKeyManager } from '../../crypto/groupKeyManager';
import { Group, User } from '../../types';
import { GroupAvatar } from './GroupAvatar';
import { GroupMemberPicker } from './GroupMemberPicker';
import { groupErrorMessage } from '../../utils/groupErrorMessages';
import { GROUP_PHOTO_ACCEPT, validateGroupPhotoFile } from '../../utils/groupImage';

interface CreateGroupModalProps {
  currentUserId: number;
  onClose: () => void;
  onCreated: (group: Group) => void;
}

const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;

// Deliberately a plain <div>, not a <form> -- GroupMemberPicker below renders its own <form> for
// the member-search input (needed so it also works standalone inside AddMembersModal, which is
// NOT a form), and nesting a <form> inside a <form> is invalid HTML. Browsers handle that
// malformed nesting by collapsing the two into a single form element, so pressing Enter in the
// search field submitted the *outer* form as a native, full-page action -- bypassing React's
// synthetic handleSubmit entirely and wiping out the in-progress group-creation state (name,
// description, picked members, selected photo). Enter-to-submit on the name field is preserved
// explicitly via onKeyDown instead of relying on native <form> submission.
export const CreateGroupModal: React.FC<CreateGroupModalProps> = ({ currentUserId, onClose, onCreated }) => {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [members, setMembers] = useState<User[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [localPreviewUrl, setLocalPreviewUrl] = useState<string | null>(null);
  const [photoError, setPhotoError] = useState<string | null>(null);

  // Once the group itself has been created (POST /groups succeeded), name/description/members can
  // no longer change -- what's left is finishing the best-effort photo upload. Kept distinct from
  // `submitting` so a photo-upload failure can offer Retry/Skip without re-running group creation.
  const [createdGroup, setCreatedGroup] = useState<Group | null>(null);
  const [photoUploadState, setPhotoUploadState] = useState<'idle' | 'uploading' | 'error'>('idle');
  const [photoUploadError, setPhotoUploadError] = useState<string | null>(null);

  useEffect(() => {
    return () => {
      if (localPreviewUrl) {
        URL.revokeObjectURL(localPreviewUrl);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const canSubmit = name.trim().length > 0 && !submitting && !createdGroup;

  const handleSelectPhoto = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    const validationError = validateGroupPhotoFile(file);
    if (validationError) {
      setPhotoError(validationError);
      return;
    }

    setPhotoError(null);
    setSelectedFile(file);
    setLocalPreviewUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return URL.createObjectURL(file);
    });
  };

  const handleRemovePhoto = () => {
    setSelectedFile(null);
    setPhotoError(null);
    setLocalPreviewUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return null;
    });
  };

  const uploadPhoto = async (group: Group, file: File) => {
    setPhotoUploadState('uploading');
    setPhotoUploadError(null);
    try {
      const updated = await groupApi.uploadAvatar(group.id, file);
      onCreated(updated);
    } catch (err) {
      setPhotoUploadState('error');
      setPhotoUploadError(groupErrorMessage(err, "Couldn't upload the group photo."));
    }
  };

  const finishAfterGroupCreated = async (group: Group) => {
    // Initial members are added one at a time via the invitation endpoint (the only mechanism
    // the backend exposes -- group creation itself only ever produces the owner's membership).
    // Best-effort: one member failing to be added must never undo the group that was already
    // created, so failures here are swallowed silently -- the owner can retry from "Add
    // members" inside the group afterward.
    const results = await Promise.allSettled(members.map((m) => groupApi.createInvitation(group.id, m.id)));
    const anyDirectAdd = results.some((r) => r.status === 'fulfilled' && r.value.outcome === 'DIRECT_ADDED');
    if (anyDirectAdd) {
      // Deterministic rotation trigger -- see AddMembersModal's identical comment. The creator
      // (this actor) is already active and present, so THIS client mints and distributes the
      // new key rather than leaving it to whichever other open client notices first.
      const freshGroup = await groupApi.getGroup(group.id).catch(() => null);
      if (freshGroup) {
        groupKeyManager.ensureGroupKey(freshGroup, currentUserId).catch(() => {});
      }
    }

    if (selectedFile) {
      await uploadPhoto(group, selectedFile);
    } else {
      onCreated(group);
    }
  };

  const handleSubmit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const group = await groupApi.createGroup(name.trim(), description.trim() || undefined);
      setCreatedGroup(group);
      await finishAfterGroupCreated(group);
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't create the group."));
    } finally {
      setSubmitting(false);
    }
  };

  const handleNameKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSubmit();
    }
  };

  const previewUrl = localPreviewUrl;
  const busy = submitting || photoUploadState === 'uploading';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-500">
              <Users className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Create Group</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Start a group chat</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
            aria-label="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {createdGroup && photoUploadState === 'error' ? (
          <div className="space-y-4">
            <div className="flex justify-center">
              <GroupAvatar name={createdGroup.name} avatarUrl={previewUrl} size="xl" />
            </div>
            <p className="text-sm text-center text-slate-600 dark:text-slate-300">
              <strong className="font-semibold">{createdGroup.name}</strong> was created, but the photo
              couldn't be uploaded.
            </p>
            {photoUploadError && <p className="text-xs text-rose-500 font-medium text-center">{photoUploadError}</p>}
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => onCreated(createdGroup)}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-semibold bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 transition-colors"
              >
                Skip photo
              </button>
              <button
                type="button"
                onClick={() => selectedFile && uploadPhoto(createdGroup, selectedFile)}
                disabled={busy}
                className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors disabled:opacity-50"
              >
                {busy && <Loader2 className="w-4 h-4 animate-spin" />}
                Retry upload
              </button>
            </div>
          </div>
        ) : (
          <>
            <div className="flex flex-col items-center gap-2">
              <div className="relative">
                <GroupAvatar name={name || 'Group'} avatarUrl={previewUrl} size="xl" />
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={busy}
                  className="absolute bottom-0 right-0 w-7 h-7 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white flex items-center justify-center shadow-md transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
                  aria-label={previewUrl ? 'Replace group photo' : 'Add group photo'}
                  title={previewUrl ? 'Replace group photo' : 'Add group photo'}
                >
                  <Camera className="w-3.5 h-3.5" />
                </button>
                {previewUrl && (
                  <button
                    type="button"
                    onClick={handleRemovePhoto}
                    disabled={busy}
                    className="absolute bottom-0 left-0 w-7 h-7 rounded-full bg-rose-500 hover:bg-rose-400 text-white flex items-center justify-center shadow-md transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
                    aria-label="Remove selected photo"
                    title="Remove selected photo"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept={GROUP_PHOTO_ACCEPT}
                className="hidden"
                onChange={handleSelectPhoto}
              />
              {photoError && <p className="text-xs text-rose-500 font-medium">{photoError}</p>}
            </div>

            <div className="space-y-1.5">
              <label className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
                Group name
              </label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value.slice(0, NAME_MAX))}
                onKeyDown={handleNameKeyDown}
                placeholder="e.g. Weekend Trip"
                className="w-full px-3.5 py-2.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-xl text-sm outline-none focus:border-indigo-500/50 transition-colors"
                autoFocus
              />
            </div>

            <div className="space-y-1.5">
              <label className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
                Description <span className="normal-case font-normal text-slate-400">(optional)</span>
              </label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value.slice(0, DESCRIPTION_MAX))}
                placeholder="What's this group about?"
                rows={2}
                className="w-full px-3.5 py-2.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-xl text-sm outline-none focus:border-indigo-500/50 transition-colors resize-none"
              />
            </div>

            <div className="space-y-1.5">
              <label className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
                Members <span className="normal-case font-normal text-slate-400">(optional)</span>
              </label>
              <GroupMemberPicker
                selected={members}
                onChange={setMembers}
                excludeUserIds={new Set([currentUserId])}
              />
            </div>

            {error && <p className="text-xs text-rose-500 font-medium">{error}</p>}

            <button
              type="button"
              onClick={() => handleSubmit()}
              disabled={!canSubmit}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors disabled:opacity-50"
            >
              {busy && <Loader2 className="w-4 h-4 animate-spin" />}
              Create Group
            </button>
          </>
        )}
      </div>
    </div>
  );
};
