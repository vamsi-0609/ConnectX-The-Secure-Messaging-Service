import React from 'react';
import { LogOut, Loader2 } from 'lucide-react';

interface LeaveGroupConfirmDialogProps {
  groupName: string;
  leaving: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export const LeaveGroupConfirmDialog: React.FC<LeaveGroupConfirmDialogProps> = ({
  groupName,
  leaving,
  onCancel,
  onConfirm,
}) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div
        className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white"
        role="dialog"
        aria-labelledby="leave-group-title"
        aria-modal="true"
      >
        <div className="flex items-start gap-3">
          <div className="p-2 rounded-xl bg-red-500/10 text-red-500 flex-shrink-0">
            <LogOut className="w-5 h-5" />
          </div>
          <div className="space-y-2">
            <h2 id="leave-group-title" className="text-lg font-bold">
              Leave {groupName}?
            </h2>
            <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              You won't be able to send or receive messages in this group unless someone adds you back.
            </p>
          </div>
        </div>

        <div className="flex items-center justify-end gap-3 pt-1">
          <button
            type="button"
            onClick={onCancel}
            disabled={leaving}
            className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={leaving}
            className="px-4 py-2 rounded-xl text-sm font-semibold bg-red-600 hover:bg-red-500 text-white transition-colors disabled:opacity-50 inline-flex items-center gap-2"
          >
            {leaving && <Loader2 className="w-4 h-4 animate-spin" />}
            Leave Group
          </button>
        </div>
      </div>
    </div>
  );
};
