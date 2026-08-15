import React, { useEffect } from 'react';
import { X, MessageSquare } from 'lucide-react';
import { resolveProfileImageUrl } from '../../utils/profileImage';

export interface ToastNotificationData {
  id: string;
  senderName: string;
  senderAvatar?: string;
  messageText: string;
  conversationId: number;
  timestamp: string;
}

interface NotificationToastProps {
  toast: ToastNotificationData | null;
  onDismiss: () => void;
  onClickToast: (conversationId: number) => void;
}

export const NotificationToast: React.FC<NotificationToastProps> = ({
  toast,
  onDismiss,
  onClickToast,
}) => {
  useEffect(() => {
    if (!toast) return;

    const timer = setTimeout(() => {
      onDismiss();
    }, 6000);

    return () => clearTimeout(timer);
  }, [toast, onDismiss]);

  if (!toast) return null;

  const avatarUrl = resolveProfileImageUrl(toast.senderAvatar);

  return (
    <div className="fixed top-4 right-4 z-50 max-w-sm w-full bg-slate-900/95 dark:bg-slate-900/95 text-white border border-indigo-500/30 rounded-2xl shadow-2xl backdrop-blur-md p-4 transition-all duration-300 transform translate-y-0 animate-in fade-in slide-in-from-top-3 select-none">
      <div className="flex items-start gap-3">
        <div
          onClick={() => onClickToast(toast.conversationId)}
          className="flex-shrink-0 relative cursor-pointer"
        >
          {avatarUrl ? (
            <img
              src={avatarUrl}
              alt={toast.senderName}
              className="w-10 h-10 rounded-full object-cover ring-2 ring-indigo-500/40"
            />
          ) : (
            <div className="w-10 h-10 rounded-full bg-indigo-600 flex items-center justify-center text-white font-bold text-sm shadow-md">
              {toast.senderName.charAt(0).toUpperCase()}
            </div>
          )}
          <span className="absolute -bottom-0.5 -right-0.5 w-3.5 h-3.5 bg-emerald-500 border-2 border-slate-900 rounded-full" />
        </div>

        <div
          onClick={() => onClickToast(toast.conversationId)}
          className="flex-1 min-w-0 cursor-pointer"
        >
          <div className="flex items-center justify-between gap-2">
            <h4 className="text-sm font-semibold text-slate-100 truncate">
              {toast.senderName}
            </h4>
            <span className="text-[11px] text-slate-400 flex-shrink-0">
              {new Date(toast.timestamp).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
          </div>

          <p className="text-xs text-slate-300 truncate mt-0.5 font-normal">
            {toast.messageText}
          </p>

          <span className="inline-flex items-center gap-1 text-[11px] text-indigo-400 font-medium mt-1">
            <MessageSquare className="w-3 h-3" />
            <span>Click to view message</span>
          </span>
        </div>

        <button
          onClick={onDismiss}
          className="text-slate-400 hover:text-white p-1 rounded-full hover:bg-slate-800 transition-colors flex-shrink-0"
          aria-label="Close notification"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};
