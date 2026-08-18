import React from 'react';
import { Check, XCircle, Loader2 } from 'lucide-react';
import { GroupInvitation } from '../../types';
import { GroupAvatar } from './GroupAvatar';

interface GroupInvitationCardProps {
  invitation: GroupInvitation;
  busy: boolean;
  onAccept: () => void;
  onDecline: () => void;
}

// A received, still-pending group invitation. Deliberately shows only "Invited you to join" --
// never the raw PENDING status string or any other backend-internal label.
export const GroupInvitationCard: React.FC<GroupInvitationCardProps> = ({ invitation, busy, onAccept, onDecline }) => {
  return (
    <div className="flex items-center justify-between p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl gap-2">
      <div className="flex items-center gap-3 min-w-0">
        <GroupAvatar name={invitation.groupName} size="sm" />
        <div className="min-w-0">
          <h4 className="text-sm font-bold truncate">{invitation.groupName}</h4>
          <p className="text-xs text-slate-500 dark:text-slate-400 truncate">
            {invitation.invitedBy.displayName || invitation.invitedBy.username} invited you to join
          </p>
        </div>
      </div>
      <div className="flex items-center gap-1.5 flex-shrink-0">
        <button
          onClick={onAccept}
          disabled={busy}
          className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold rounded-xl shadow-sm transition-all flex items-center gap-1.5 disabled:opacity-50"
        >
          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
          <span>Join</span>
        </button>
        <button
          onClick={onDecline}
          disabled={busy}
          className="px-3 py-1.5 bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-xs font-semibold rounded-xl transition-all disabled:opacity-50"
        >
          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
        </button>
      </div>
    </div>
  );
};
