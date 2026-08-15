import React, { useMemo, useState } from 'react';
import { X, Forward, Search, Check, Loader2, Image as ImageIcon, FileText, MapPin } from 'lucide-react';
import { Conversation, Message, User } from '../../types';
import { UserAvatar } from '../common/UserAvatar';

interface ForwardMessageModalProps {
  open: boolean;
  messages: Message[];
  conversations: Conversation[];
  currentUserId: number;
  onClose: () => void;
  onForward: (targetConversationIds: number[]) => Promise<{ conversationId: number; success: boolean; error?: string }[]>;
}

function getRecipient(conv: Conversation, currentUserId: number): User | null {
  if (!conv.members?.length) return null;
  const member = conv.members.find((m) => m.user?.id != null && Number(m.user.id) !== Number(currentUserId));
  return member?.user ?? null;
}

function messagePreviewLabel(message: Message): { icon: React.ReactNode; text: string } {
  if (message.messageType === 'IMAGE') {
    return { icon: <ImageIcon className="w-3.5 h-3.5" />, text: message.caption || 'Photo' };
  }
  if (message.messageType === 'DOCUMENT') {
    return { icon: <FileText className="w-3.5 h-3.5" />, text: message.caption || 'Document' };
  }
  if (message.messageType === 'LOCATION') {
    return { icon: <MapPin className="w-3.5 h-3.5" />, text: message.locationLabel || 'Location' };
  }
  return { icon: null, text: message.decryptedContent || 'Message' };
}

export const ForwardMessageModal: React.FC<ForwardMessageModalProps> = ({
  open,
  messages,
  conversations,
  currentUserId,
  onClose,
  onForward,
}) => {
  const [query, setQuery] = useState('');
  const [selectedConvIds, setSelectedConvIds] = useState<Set<number>>(new Set());
  const [sending, setSending] = useState(false);
  const [errorSummary, setErrorSummary] = useState<string | null>(null);

  const filteredConversations = useMemo(() => {
    const q = query.trim().toLowerCase();
    return conversations.filter((conv) => {
      const recipient = getRecipient(conv, currentUserId);
      if (!recipient) return false;
      if (!q) return true;
      const name = (recipient.displayName || recipient.username || '').toLowerCase();
      return name.includes(q) || (recipient.username || '').toLowerCase().includes(q);
    });
  }, [conversations, currentUserId, query]);

  if (!open) return null;

  const toggleConv = (id: number) => {
    setSelectedConvIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleSend = async () => {
    if (selectedConvIds.size === 0 || sending) return;
    setSending(true);
    setErrorSummary(null);
    try {
      const results = await onForward(Array.from(selectedConvIds));
      const failures = results.filter((r) => !r.success);
      if (failures.length === 0) {
        onClose();
      } else if (failures.length === results.length) {
        setErrorSummary(failures[0]?.error || 'Failed to forward message.');
      } else {
        setErrorSummary(`Forwarded to ${results.length - failures.length} of ${results.length} chats. Some failed.`);
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to forward message';
      setErrorSummary(message);
    } finally {
      setSending(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[75] flex items-center justify-center bg-black/60 backdrop-blur-sm p-3 sm:p-6 select-none"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md bg-slate-950 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[85vh] animate-pop-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800 flex-shrink-0">
          <div className="flex items-center gap-2 text-white">
            <Forward className="w-5 h-5 text-indigo-400" />
            <h3 className="font-semibold text-sm sm:text-base">
              Forward {messages.length > 1 ? `${messages.length} messages` : 'message'}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-white rounded-lg hover:bg-slate-800 transition-colors"
            aria-label="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Forwarding preview */}
        <div className="px-4 py-3 border-b border-slate-800 flex-shrink-0 space-y-1.5 max-h-28 overflow-y-auto">
          {messages.map((m) => {
            const preview = messagePreviewLabel(m);
            return (
              <div
                key={m.id}
                className="flex items-center gap-2 text-xs text-slate-300 bg-slate-900/60 border border-slate-800 rounded-lg px-2.5 py-1.5"
              >
                {preview.icon}
                <span className="truncate">{preview.text}</span>
              </div>
            );
          })}
        </div>

        <div className="px-4 py-3 border-b border-slate-800 flex-shrink-0">
          <div className="relative">
            <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search chats..."
              className="w-full pl-9 pr-3 py-2 rounded-xl bg-slate-900 border border-slate-700 text-sm text-white placeholder-slate-500 outline-none focus:border-indigo-500 select-text"
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-2 py-2 space-y-1 min-h-[120px]">
          {filteredConversations.length === 0 ? (
            <div className="text-center py-8 text-xs text-slate-500">No chats found.</div>
          ) : (
            filteredConversations.map((conv) => {
              const recipient = getRecipient(conv, currentUserId);
              if (!recipient) return null;
              const isSelected = selectedConvIds.has(conv.id);
              return (
                <button
                  key={conv.id}
                  type="button"
                  onClick={() => toggleConv(conv.id)}
                  className={`w-full flex items-center gap-3 px-2.5 py-2 rounded-xl transition-colors text-left ${
                    isSelected ? 'bg-indigo-600/15' : 'hover:bg-slate-900/70'
                  }`}
                >
                  <UserAvatar user={recipient} size="sm" passive />
                  <span className="flex-1 min-w-0 truncate text-sm text-slate-200">
                    {recipient.displayName || recipient.username}
                  </span>
                  <span
                    className={`w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0 transition-colors ${
                      isSelected ? 'bg-indigo-500 border-indigo-500' : 'border-slate-600'
                    }`}
                  >
                    {isSelected && <Check className="w-3 h-3 text-white" />}
                  </span>
                </button>
              );
            })
          )}
        </div>

        {errorSummary && (
          <div className="px-4 py-2 text-xs text-rose-400 border-t border-slate-800 flex-shrink-0">{errorSummary}</div>
        )}

        <div className="px-4 py-3 border-t border-slate-800 flex-shrink-0">
          <button
            type="button"
            onClick={handleSend}
            disabled={selectedConvIds.size === 0 || sending}
            className="w-full py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold inline-flex items-center justify-center gap-2"
          >
            {sending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Forward className="w-4 h-4" />}
            {selectedConvIds.size > 0 ? `Forward to ${selectedConvIds.size}` : 'Select a chat'}
          </button>
        </div>
      </div>
    </div>
  );
};
