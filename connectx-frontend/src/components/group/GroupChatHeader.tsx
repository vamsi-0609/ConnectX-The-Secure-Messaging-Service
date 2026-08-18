import React, { useState } from 'react';
import { ArrowLeft, Info, MoreVertical } from 'lucide-react';
import { Group } from '../../types';
import { GroupAvatar } from './GroupAvatar';

interface GroupChatHeaderProps {
  group: Group | null;
  showInfoDrawer: boolean;
  onToggleInfoDrawer: () => void;
  onBack?: () => void;
}

// Deliberately distinct from ChatHeader (1:1) rather than a retrofit: no online/typing status
// (groups don't have one), a member count instead, and no voice/video call buttons.
export const GroupChatHeader: React.FC<GroupChatHeaderProps> = ({ group, showInfoDrawer, onToggleInfoDrawer, onBack }) => {
  const [showMenu, setShowMenu] = useState(false);
  const name = group?.name || 'Group';
  const memberCount = group?.activeMemberCount ?? 0;

  return (
    <div className="h-[60px] min-h-[60px] md:h-[68px] md:min-h-[68px] px-2 md:px-6 border-b border-slate-200/80 dark:border-slate-800/80 bg-white dark:bg-[#0f172a] flex items-center justify-between select-none">
      <div className="flex items-center gap-1 min-w-0 flex-1">
        {onBack && (
          <button
            onClick={onBack}
            className="md:hidden p-2 text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-lg transition-colors flex-shrink-0"
            aria-label="Back to conversations"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
        )}

        <button
          type="button"
          onClick={onToggleInfoDrawer}
          disabled={!group}
          className="flex items-center gap-2.5 min-w-0 flex-1 text-left"
          aria-label="View group info"
        >
          <GroupAvatar name={name} avatarUrl={group?.avatarUrl} size="sm" className="md:w-11 md:h-11" />
          <div className="min-w-0 flex-1">
            <h2 className="font-semibold text-slate-900 dark:text-white text-[15px] md:text-[16px] truncate leading-tight">
              {name}
            </h2>
            <p className="text-[11px] md:text-xs truncate leading-tight mt-0.5 text-slate-500 dark:text-slate-400">
              {group ? `${memberCount} ${memberCount === 1 ? 'member' : 'members'}` : 'Loading group...'}
            </p>
          </div>
        </button>
      </div>

      <div className="flex items-center gap-0.5 md:gap-1 flex-shrink-0 relative">
        <button
          onClick={() => setShowMenu((v) => !v)}
          className="p-2 md:p-2.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
          aria-label="Group options"
        >
          <MoreVertical className="w-5 h-5" />
        </button>

        {showMenu && (
          <>
            <div className="fixed inset-0 z-20" onClick={() => setShowMenu(false)} />
            <div className="absolute right-0 top-full mt-1 w-48 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-30 py-1 text-sm">
              <button
                onClick={() => {
                  onToggleInfoDrawer();
                  setShowMenu(false);
                }}
                className={`w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 ${
                  showInfoDrawer ? 'text-indigo-500' : 'text-slate-700 dark:text-slate-200'
                }`}
              >
                <Info className="w-4 h-4" /> Group info
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
