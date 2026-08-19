import React, { useRef, useState } from 'react';
import { Camera, Loader2, Pencil, Trash2, X } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { Group } from '../../types';
import { GroupAvatar } from './GroupAvatar';
import { groupErrorMessage } from '../../utils/groupErrorMessages';
import { GROUP_PHOTO_ACCEPT, validateGroupPhotoFile } from '../../utils/groupImage';

interface EditGroupModalProps {
  group: Group;
  onClose: () => void;
  onGroupUpdated: (group: Group) => void;
}

const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;

// Photo change/remove apply immediately (same proven groupApi.uploadAvatar/removeAvatar calls
// CreateGroupModal already uses), independent of Save -- reusing that already-working flow as-is
// rather than re-staging a file upload behind this screen's own Save button, per the explicit
// instruction not to regress the existing group photo functionality. Name/description are staged
// locally and only committed to the server on Save (groupApi.updateInfo), so a change one didn't
// mean to make is easy to back out of with Cancel.
export const EditGroupModal: React.FC<EditGroupModalProps> = ({ group, onClose, onGroupUpdated }) => {
  const [name, setName] = useState(group.name);
  const [description, setDescription] = useState(group.description || '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [photoBusy, setPhotoBusy] = useState(false);
  const [photoError, setPhotoError] = useState<string | null>(null);

  const canSave = name.trim().length > 0 && !saving;

  const handleSelectPhoto = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    const validationError = validateGroupPhotoFile(file);
    if (validationError) {
      setPhotoError(validationError);
      return;
    }

    setPhotoError(null);
    setPhotoBusy(true);
    try {
      const updated = await groupApi.uploadAvatar(group.id, file);
      onGroupUpdated(updated);
    } catch (err) {
      setPhotoError(groupErrorMessage(err, "Couldn't upload the group photo."));
    } finally {
      setPhotoBusy(false);
    }
  };

  const handleRemovePhoto = async () => {
    setPhotoError(null);
    setPhotoBusy(true);
    try {
      const updated = await groupApi.removeAvatar(group.id);
      onGroupUpdated(updated);
    } catch (err) {
      setPhotoError(groupErrorMessage(err, "Couldn't remove the group photo."));
    } finally {
      setPhotoBusy(false);
    }
  };

  const handleSave = async () => {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await groupApi.updateInfo(group.id, {
        name: name.trim(),
        description: description.trim(),
      });
      onGroupUpdated(updated);
      onClose();
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't save group info."));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-500">
              <Pencil className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Edit Group</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Update photo, name & description</p>
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

        <div className="flex flex-col items-center gap-2">
          <div className="relative">
            <GroupAvatar name={group.name} avatarUrl={group.avatarUrl} size="xl" />
            {photoBusy && (
              <div className="absolute inset-0 rounded-full bg-slate-950/50 flex items-center justify-center">
                <Loader2 className="w-6 h-6 text-white animate-spin" />
              </div>
            )}
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={photoBusy}
              className="absolute bottom-0 right-0 w-7 h-7 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white flex items-center justify-center shadow-md transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
              aria-label={group.avatarUrl ? 'Replace group photo' : 'Add group photo'}
              title={group.avatarUrl ? 'Replace group photo' : 'Add group photo'}
            >
              <Camera className="w-3.5 h-3.5" />
            </button>
            {group.avatarUrl && (
              <button
                type="button"
                onClick={handleRemovePhoto}
                disabled={photoBusy}
                className="absolute bottom-0 left-0 w-7 h-7 rounded-full bg-rose-500 hover:bg-rose-400 text-white flex items-center justify-center shadow-md transition-transform hover:scale-105 active:scale-95 disabled:opacity-50"
                aria-label="Remove group photo"
                title="Remove group photo"
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
            placeholder="Group name"
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
            rows={3}
            className="w-full px-3.5 py-2.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-xl text-sm outline-none focus:border-indigo-500/50 transition-colors resize-none"
          />
        </div>

        {error && <p className="text-xs text-rose-500 font-medium">{error}</p>}

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="flex-1 px-4 py-2.5 rounded-xl text-sm font-semibold bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-200 transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={!canSave}
            className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors disabled:opacity-50"
          >
            {saving && <Loader2 className="w-4 h-4 animate-spin" />}
            Save
          </button>
        </div>
      </div>
    </div>
  );
};
