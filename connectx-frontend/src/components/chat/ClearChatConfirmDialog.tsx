import React from 'react';
import { AlertTriangle, Loader2 } from 'lucide-react';

interface ClearChatConfirmDialogProps {
  contactName: string;
  clearing: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export const ClearChatConfirmDialog: React.FC<ClearChatConfirmDialogProps> = ({
  contactName,
  clearing,
  onCancel,
  onConfirm,
}) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in select-none">
      <div
        className="w-full max-w-md bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white"
        role="dialog"
        aria-labelledby="clear-chat-title"
        aria-modal="true"
      >
        <div className="flex items-start gap-3">
          <div className="p-2 rounded-xl bg-red-500/10 text-red-500 flex-shrink-0">
            <AlertTriangle className="w-5 h-5" />
          </div>
          <div className="space-y-2">
            <h2 id="clear-chat-title" className="text-lg font-bold">
              Clear chat?
            </h2>
            <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              This will clear all messages from your chat with{' '}
              <span className="font-medium text-slate-800 dark:text-slate-200">{contactName}</span>.
              The other person will still have their copy of the chat.
            </p>
          </div>
        </div>

        <div className="flex items-center justify-end gap-3 pt-1">
          <button
            type="button"
            onClick={onCancel}
            disabled={clearing}
            className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={clearing}
            className="px-4 py-2 rounded-xl text-sm font-semibold bg-red-600 hover:bg-red-500 text-white transition-colors disabled:opacity-50 inline-flex items-center gap-2"
          >
            {clearing && <Loader2 className="w-4 h-4 animate-spin" />}
            Clear Chat
          </button>
        </div>
      </div>
    </div>
  );
};
