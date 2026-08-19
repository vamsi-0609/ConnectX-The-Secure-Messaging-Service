import React from 'react';
import { Lock, Loader2 } from 'lucide-react';
import { Group } from '../../types';

interface GroupComposerPlaceholderProps {
  group: Group | null;
}

// Group messaging is fully implemented (real MessageInput, see ChatScreen's isGroup branch) --
// this renders only as the fallback for the two cases that aren't a live composer: the group
// hasn't finished loading yet (group or group.currentUserRole still unset, right after opening a
// group), or who_can_send_messages=ADMINS_ONLY blocks this MEMBER. It still surfaces the real,
// backend-authoritative who_can_send_messages state, since that's pure UI feedback with no crypto
// involved -- ChatScreen's canSendInGroup mirrors this exact same check to decide which composer
// to render.
export const GroupComposerPlaceholder: React.FC<GroupComposerPlaceholderProps> = ({ group }) => {
  const loading = !group || !group.currentUserRole;
  const sendRestricted = !loading && group.whoCanSendMessages === 'ADMINS_ONLY' && group.currentUserRole === 'MEMBER';

  return (
    <footer className="chat-composer flex-shrink-0 px-4 py-3.5 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800/80 select-none">
      <div className="flex items-center justify-center gap-2 py-1.5 text-sm text-slate-500 dark:text-slate-400">
        {loading ? (
          <>
            <Loader2 className="w-4 h-4 flex-shrink-0 animate-spin" />
            <span>Loading conversation...</span>
          </>
        ) : sendRestricted ? (
          <>
            <Lock className="w-4 h-4 flex-shrink-0" />
            <span>Only admins can send messages.</span>
          </>
        ) : null}
      </div>
    </footer>
  );
};
