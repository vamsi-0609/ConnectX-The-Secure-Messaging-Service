import React, { useState } from 'react';
import { Users, X, Loader2, Camera } from 'lucide-react';
import { groupApi } from '../../api/groupApi';
import { Group, User } from '../../types';
import { GroupAvatar } from './GroupAvatar';
import { GroupMemberPicker } from './GroupMemberPicker';
import { groupErrorMessage } from '../../utils/groupErrorMessages';

interface CreateGroupModalProps {
  currentUserId: number;
  onClose: () => void;
  onCreated: (group: Group) => void;
}

const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;

export const CreateGroupModal: React.FC<CreateGroupModalProps> = ({ currentUserId, onClose, onCreated }) => {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [members, setMembers] = useState<User[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = name.trim().length > 0 && !submitting;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const group = await groupApi.createGroup(name.trim(), description.trim() || undefined);

      // Initial members are added one at a time via the invitation endpoint (the only mechanism
      // the backend exposes -- group creation itself only ever produces the owner's membership).
      // Best-effort: one member failing to be added must never undo the group that was already
      // created, so failures here are swallowed silently -- the owner can retry from "Add
      // members" inside the group afterward.
      await Promise.allSettled(members.map((m) => groupApi.createInvitation(group.id, m.id)));

      onCreated(group);
    } catch (err) {
      setError(groupErrorMessage(err, "Couldn't create the group."));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white max-h-[90vh] overflow-y-auto"
      >
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

        <div className="flex justify-center">
          <div className="relative">
            <GroupAvatar name={name || 'Group'} size="xl" />
            <div
              className="absolute bottom-0 right-0 w-7 h-7 rounded-full bg-slate-200 dark:bg-slate-700 flex items-center justify-center text-slate-500 dark:text-slate-400"
              title="Group photos are coming in a future update"
            >
              <Camera className="w-3.5 h-3.5" />
            </div>
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
            Group name
          </label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value.slice(0, NAME_MAX))}
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
          type="submit"
          disabled={!canSubmit}
          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors disabled:opacity-50"
        >
          {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
          Create Group
        </button>
      </form>
    </div>
  );
};
