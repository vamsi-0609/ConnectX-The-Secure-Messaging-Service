import React, { useState } from 'react';
import { BellOff, Loader2 } from 'lucide-react';

interface MuteChatModalProps {
  open: boolean;
  contactName: string;
  onClose: () => void;
  onConfirmMute: (duration: '8_HOURS' | '1_WEEK' | 'ALWAYS') => Promise<void>;
}

export const MuteChatModal: React.FC<MuteChatModalProps> = ({
  open,
  contactName,
  onClose,
  onConfirmMute,
}) => {
  const [selectedDuration, setSelectedDuration] = useState<'8_HOURS' | '1_WEEK' | 'ALWAYS'>('8_HOURS');
  const [busy, setBusy] = useState(false);

  if (!open) return null;

  const handleConfirm = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await onConfirmMute(selectedDuration);
      onClose();
    } catch (err) {
      console.error('Failed to mute chat:', err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in select-none">
      <div className="bg-slate-900 border border-slate-700/80 rounded-2xl max-w-sm w-full p-5 shadow-2xl space-y-4">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-full bg-indigo-500/15 text-indigo-400">
            <BellOff className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-semibold text-white">Mute notifications</h3>
            <p className="text-xs text-slate-400">for {contactName}</p>
          </div>
        </div>

        <p className="text-xs text-slate-300">
          Other participants won't see that you muted this chat. You will still receive messages.
        </p>

        <div className="space-y-2 py-1">
          <label className="flex items-center gap-3 p-2.5 rounded-xl hover:bg-slate-800 cursor-pointer text-sm text-slate-200">
            <input
              type="radio"
              name="muteDuration"
              value="8_HOURS"
              checked={selectedDuration === '8_HOURS'}
              onChange={() => setSelectedDuration('8_HOURS')}
              className="text-indigo-600 focus:ring-indigo-500"
            />
            <span>8 Hours</span>
          </label>

          <label className="flex items-center gap-3 p-2.5 rounded-xl hover:bg-slate-800 cursor-pointer text-sm text-slate-200">
            <input
              type="radio"
              name="muteDuration"
              value="1_WEEK"
              checked={selectedDuration === '1_WEEK'}
              onChange={() => setSelectedDuration('1_WEEK')}
              className="text-indigo-600 focus:ring-indigo-500"
            />
            <span>1 Week</span>
          </label>

          <label className="flex items-center gap-3 p-2.5 rounded-xl hover:bg-slate-800 cursor-pointer text-sm text-slate-200">
            <input
              type="radio"
              name="muteDuration"
              value="ALWAYS"
              checked={selectedDuration === 'ALWAYS'}
              onChange={() => setSelectedDuration('ALWAYS')}
              className="text-indigo-600 focus:ring-indigo-500"
            />
            <span>Always</span>
          </label>
        </div>

        <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-800">
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="px-4 py-2 text-xs font-medium text-slate-400 hover:text-white rounded-lg hover:bg-slate-800 transition-colors"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={busy}
            className="px-4 py-2 text-xs font-medium bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 text-white rounded-lg transition-colors flex items-center gap-1.5"
          >
            {busy && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            <span>Mute</span>
          </button>
        </div>
      </div>
    </div>
  );
};
