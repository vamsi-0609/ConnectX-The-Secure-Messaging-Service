import React from 'react';
import { Lock, Clock3 } from 'lucide-react';
import { Group } from '../../types';

interface GroupComposerPlaceholderProps {
  group: Group | null;
}

// Replaces MessageInput for GROUP conversations in this stage -- group message encryption isn't
// implemented yet (see CONNECTX_GROUP_IMPLEMENTATION_STATE.md Stage 6D+), so this is an honest
// placeholder rather than a composer that silently does nothing when submitted. It still surfaces
// the real, backend-authoritative who_can_send_messages state, since that's pure UI feedback with
// no crypto involved.
export const GroupComposerPlaceholder: React.FC<GroupComposerPlaceholderProps> = ({ group }) => {
  const sendRestricted = group?.whoCanSendMessages === 'ADMINS_ONLY' && group.currentUserRole === 'MEMBER';

  return (
    <footer className="chat-composer flex-shrink-0 px-4 py-3.5 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800/80 select-none">
      <div className="flex items-center justify-center gap-2 py-1.5 text-sm text-slate-500 dark:text-slate-400">
        {sendRestricted ? (
          <>
            <Lock className="w-4 h-4 flex-shrink-0" />
            <span>Only admins can send messages.</span>
          </>
        ) : (
          <>
            <Clock3 className="w-4 h-4 flex-shrink-0" />
            <span>Group messaging is coming in an upcoming update.</span>
          </>
        )}
      </div>
    </footer>
  );
};
