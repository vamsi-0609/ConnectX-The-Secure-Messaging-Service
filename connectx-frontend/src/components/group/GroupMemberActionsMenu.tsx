import React from 'react';
import { ShieldPlus, ShieldMinus, UserMinus, Loader2 } from 'lucide-react';
import { GroupRole } from '../../types';

interface GroupMemberActionsMenuProps {
  viewerRole: GroupRole;
  targetRole: GroupRole;
  busy: boolean;
  onPromote: () => void;
  onDemote: () => void;
  onRemove: () => void;
  onClose: () => void;
}

// Hiding a button here is UX only, never authorization -- GroupAuthorizationService re-derives
// and re-enforces every one of these rules server-side (requireCanChangeRole,
// requireCanRemoveMember) regardless of what this menu shows. Mirrors that matrix purely so the
// UI doesn't offer an action the backend would reject:
//   promote/demote: OWNER only, target must not be OWNER.
//   remove: OWNER may remove any non-owner; ADMIN may remove a MEMBER only; MEMBER removes no one.
// The viewer's own row and the group's OWNER row never render this menu at all (see
// GroupMembersScreen) -- "leave group" is the self-action, and the owner can't be modified here.
export const GroupMemberActionsMenu: React.FC<GroupMemberActionsMenuProps> = ({
  viewerRole,
  targetRole,
  busy,
  onPromote,
  onDemote,
  onRemove,
  onClose,
}) => {
  const canChangeRole = viewerRole === 'OWNER' && targetRole !== 'OWNER';
  const canRemove =
    viewerRole === 'OWNER' ? targetRole !== 'OWNER' : viewerRole === 'ADMIN' ? targetRole === 'MEMBER' : false;

  if (!canChangeRole && !canRemove) return null;

  return (
    <>
      <div className="fixed inset-0 z-20" onClick={onClose} />
      <div className="absolute right-0 top-full mt-1 w-48 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-30 py-1 text-sm">
        {canChangeRole && targetRole === 'MEMBER' && (
          <button
            onClick={onPromote}
            disabled={busy}
            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200 disabled:opacity-50"
          >
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldPlus className="w-4 h-4" />}
            Make admin
          </button>
        )}
        {canChangeRole && targetRole === 'ADMIN' && (
          <button
            onClick={onDemote}
            disabled={busy}
            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200 disabled:opacity-50"
          >
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldMinus className="w-4 h-4" />}
            Remove as admin
          </button>
        )}
        {canRemove && (
          <button
            onClick={onRemove}
            disabled={busy}
            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-red-50 dark:hover:bg-red-950/30 text-red-600 dark:text-red-400 disabled:opacity-50"
          >
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserMinus className="w-4 h-4" />}
            Remove from group
          </button>
        )}
      </div>
    </>
  );
};
