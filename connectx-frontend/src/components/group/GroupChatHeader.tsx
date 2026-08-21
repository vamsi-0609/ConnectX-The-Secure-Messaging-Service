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

export const GroupChatHeader: React.FC<GroupChatHeaderProps> = ({
  group,
  showInfoDrawer,
  onToggleInfoDrawer,
  onBack,
}) => {
  const [showMenu, setShowMenu] = useState(false);
  const name = group?.name || 'Group';
  const memberCount = group?.activeMemberCount ?? 0;

  return (
    <div className="h-[56px] sm:h-[60px] md:h-[68px] min-h-[56px] sm:min-h-[60px] md:min-h-[68px] px-2 sm:px-4 md:px-6 border-b border-slate-200/90 dark:border-slate-800/80 bg-white/95 dark:bg-[#0a0e1a]/95 backdrop-blur-sm flex items-center justify-between gap-1.5 sm:gap-2 select-none z-20 flex-shrink-0">
      {/* Left: Back button + Group Identity */}
      <div className="flex items-center gap-1 sm:gap-2 min-w-0 flex-1">
        {onBack && (
          <button
            onClick={onBack}
            className="md:hidden w-9 h-9 sm:w-10 sm:h-10 -ml-1 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95 transition-all duration-150 motion-reduce:transition-none flex-shrink-0 cursor-pointer"
            aria-label="Back to conversations"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
        )}

        <button
          type="button"
          onClick={onToggleInfoDrawer}
          disabled={!group}
          className="flex items-center gap-2 sm:gap-2.5 min-w-0 flex-1 text-left cursor-pointer group"
          aria-label="View group info"
        >
          <GroupAvatar
            name={name}
            avatarUrl={group?.avatarUrl}
            size="sm"
            className="w-9 h-9 sm:w-10 sm:h-10 md:w-11 md:h-11 flex-shrink-0 group-hover:ring-2 group-hover:ring-violet-500/40 transition-all rounded-full"
          />
          <div className="min-w-0 flex-1">
            <h2 className="font-bold text-slate-900 dark:text-white text-[14px] sm:text-[15px] md:text-[16px] truncate leading-tight">
              {name}
            </h2>
            <p className="text-[11px] sm:text-xs truncate leading-tight mt-0.5 text-slate-500 dark:text-slate-400">
              {group ? `${memberCount} ${memberCount === 1 ? 'member' : 'members'}` : 'Loading group...'}
            </p>
          </div>
        </button>
      </div>

      {/* Right: Options Menu */}
      <div className="flex items-center gap-0.5 sm:gap-1 flex-shrink-0 relative">
        <button
          onClick={() => setShowMenu((v) => !v)}
          className={`w-9 h-9 sm:w-10 sm:h-10 flex items-center justify-center rounded-xl transition-all duration-150 motion-reduce:transition-none cursor-pointer ${
            showMenu
              ? 'text-violet-600 dark:text-violet-400 bg-slate-100 dark:bg-slate-800'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/70 active:scale-95'
          }`}
          aria-label="Group options"
        >
          <MoreVertical className="w-5 h-5" />
        </button>

        {showMenu && (
          <>
            <div className="fixed inset-0 z-30" onClick={() => setShowMenu(false)} />
            <div className="absolute right-0 top-full mt-1.5 w-48 sm:w-52 max-w-[calc(100vw-1rem)] bg-white/95 dark:bg-[#0c101c]/95 border border-slate-200/90 dark:border-slate-800/90 rounded-xl shadow-xl z-40 py-1 text-xs sm:text-sm backdrop-blur-sm animate-pop-in select-none">
              <button
                onClick={() => {
                  onToggleInfoDrawer();
                  setShowMenu(false);
                }}
                className={`w-full text-left px-3 py-2 flex items-center gap-2.5 hover:bg-slate-100/80 dark:hover:bg-slate-800/70 transition-colors cursor-pointer ${
                  showInfoDrawer
                    ? 'text-violet-600 dark:text-violet-400 font-medium'
                    : 'text-slate-700 dark:text-slate-200'
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
