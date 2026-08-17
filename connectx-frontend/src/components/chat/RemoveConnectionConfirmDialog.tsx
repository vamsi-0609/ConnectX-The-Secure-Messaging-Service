import React from 'react';
import { UserMinus, Loader2 } from 'lucide-react';

interface RemoveConnectionConfirmDialogProps {
  contactName: string;
  removing: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export const RemoveConnectionConfirmDialog: React.FC<RemoveConnectionConfirmDialogProps> = ({
  contactName,
  removing,
  onCancel,
  onConfirm,
}) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div
        className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white"
        role="dialog"
        aria-labelledby="remove-connection-title"
        aria-modal="true"
      >
        <div className="flex items-start gap-3">
          <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-500 flex-shrink-0">
            <UserMinus className="w-5 h-5" />
          </div>
          <div className="space-y-2">
            <h2 id="remove-connection-title" className="text-lg font-bold">
              Remove connection?
            </h2>
            <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              Are you sure you want to remove your connection with{' '}
              <span className="font-medium text-slate-800 dark:text-slate-200">{contactName}</span>?
            </p>
          </div>
        </div>

        <div className="flex items-center justify-end gap-3 pt-1">
          <button
            type="button"
            onClick={onCancel}
            disabled={removing}
            className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={removing}
            className="px-4 py-2 rounded-xl text-sm font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition-colors disabled:opacity-50 inline-flex items-center gap-2"
          >
            {removing && <Loader2 className="w-4 h-4 animate-spin" />}
            Remove
          </button>
        </div>
      </div>
    </div>
  );
};
