import React, { useState } from 'react';
import { Search, Plus, Lock, Trash2, Sparkles } from 'lucide-react';
import { User, Conversation, ConversationPreview } from '../../types';
import { formatConversationTime } from '../../utils/messageGroups';
import { getConversationListMeta } from '../../utils/conversationList';
import { UserAvatar } from '../common/UserAvatar';

interface ChatListSidebarProps {
  currentUser: User;
  conversations: Conversation[];
  activeConversationId: number | null;
  unreadConversationIds?: Set<number>;
  conversationPreviews: Record<number, ConversationPreview>;
  onSelectConversation: (conv: Conversation) => void;
  onOpenSearch: () => void;
  onOpenProfile?: () => void;
  onDeleteConversation?: (conversationId: number) => void;
}

export const ChatListSidebar: React.FC<ChatListSidebarProps> = ({
  currentUser,
  conversations,
  activeConversationId,
  unreadConversationIds,
  conversationPreviews,
  onSelectConversation,
  onOpenSearch,
  onOpenProfile,
  onDeleteConversation,
}) => {
  const [searchQuery, setSearchQuery] = useState('');

  const getRecipient = (conv: Conversation): User | null => {
    if (!conv.members?.length) return null;
    const member = conv.members.find((m) => m.user?.id && m.user.id !== currentUser.id);
    return member?.user ?? null;
  };

  const filteredConversations = conversations
    .filter((conv) => {
      const recipient = getRecipient(conv);
      if (!recipient) return false;

      const matchesSearch =
        recipient.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (recipient.displayName && recipient.displayName.toLowerCase().includes(searchQuery.toLowerCase()));

      return matchesSearch;
    })
    .sort((a, b) => {
      const aMeta = getConversationListMeta(a, conversationPreviews[a.id], currentUser.id);
      const bMeta = getConversationListMeta(b, conversationPreviews[b.id], currentUser.id);
      return bMeta.sortTime - aMeta.sortTime;
    });

  return (
    <div className="w-full md:w-[320px] lg:w-[340px] xl:w-[360px] h-full bg-white dark:bg-[#0f172a] border-r border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0">
      <div className="px-4 pt-4 pb-3 md:pt-5 md:pb-4 border-b border-slate-200 dark:border-slate-800/80 space-y-3 bg-slate-50/90 dark:bg-slate-950/50">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-600 via-purple-600 to-violet-500 flex items-center justify-center text-white shadow-md shadow-indigo-600/20 flex-shrink-0">
              <Sparkles className="w-4 h-4" />
            </div>
            <div className="min-w-0">
              <h1 className="text-base md:text-[17px] font-bold text-slate-900 dark:text-white leading-none">ConnectX</h1>
              <p className="text-[10px] font-semibold tracking-wider text-indigo-400 uppercase mt-0.5">E2EE Secured</p>
            </div>
          </div>

          <div className="flex items-center gap-1.5 flex-shrink-0">
            <button
              onClick={onOpenSearch}
              className="p-2 text-white bg-indigo-600 hover:bg-indigo-500 rounded-xl transition-colors shadow-sm"
              title="New conversation"
              aria-label="New conversation"
            >
              <Plus className="w-4 h-4" />
            </button>

            {onOpenProfile && (
              <button
                onClick={onOpenProfile}
                className="rounded-xl hover:opacity-90 transition-opacity"
                title="Profile & settings"
                aria-label="Open profile menu"
              >
                <UserAvatar user={currentUser} size="sm" className="rounded-xl" passive />
              </button>
            )}
          </div>
        </div>

        <div className="relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search conversations..."
            className="w-full pl-9 pr-3 py-2 md:py-2.5 bg-white dark:bg-slate-900/80 border border-slate-200 dark:border-slate-800 rounded-xl text-sm md:text-[14px] text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500/50 transition-colors"
          />
        </div>

        <div className="flex items-center gap-2">
          <span className="text-[11px] text-slate-400">{filteredConversations.length} chats</span>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {filteredConversations.length === 0 ? (
          <div className="text-center py-16 px-6 space-y-3">
            <div className="w-12 h-12 rounded-full bg-indigo-600/10 flex items-center justify-center text-indigo-400 mx-auto">
              <Lock className="w-5 h-5" />
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400">No conversations found</p>
            <button onClick={onOpenSearch} className="text-sm text-indigo-500 hover:underline font-medium">
              Start an encrypted chat
            </button>
          </div>
        ) : (
          filteredConversations.map((conv) => {
            const recipient = getRecipient(conv);
            if (!recipient) return null;
            const isActive = conv.id === activeConversationId;
            const hasUnread = unreadConversationIds?.has(conv.id);
            const listMeta = getConversationListMeta(conv, conversationPreviews[conv.id], currentUser.id);
            const previewTime = formatConversationTime(listMeta.previewSentAt);

            return (
              <div
                key={conv.id}
                onClick={() => onSelectConversation(conv)}
                className={`group relative flex items-center gap-3 pl-4 pr-3 py-3 md:min-h-[72px] md:py-3.5 cursor-pointer border-b border-slate-100 dark:border-slate-800/50 transition-colors ${
                  isActive
                    ? 'bg-indigo-600/10 dark:bg-indigo-600/15'
                    : 'hover:bg-slate-50 dark:hover:bg-slate-800/40'
                }`}
              >
                {isActive && (
                  <span className="absolute left-0 top-0 bottom-0 w-[3px] bg-indigo-500 rounded-r-full" />
                )}

                <UserAvatar user={recipient} size="md" />

                <div className="flex-1 min-w-0 flex items-stretch gap-2">
                  <div className="flex-1 min-w-0">
                    <h3 className={`text-sm md:text-[15px] font-semibold truncate ${hasUnread ? 'text-slate-900 dark:text-white' : 'text-slate-800 dark:text-slate-100'}`}>
                      {recipient.displayName || recipient.username}
                    </h3>
                    <p className={`text-xs md:text-[13px] truncate mt-0.5 ${hasUnread ? 'text-slate-700 dark:text-slate-200 font-medium' : 'text-slate-500 dark:text-slate-400'}`}>
                      {listMeta.previewText}
                    </p>
                  </div>

                  <div className="flex flex-col items-end justify-between shrink-0 self-stretch py-0.5">
                    <span className={`text-[11px] md:text-xs whitespace-nowrap leading-none ${hasUnread ? 'text-indigo-500 font-medium' : 'text-slate-400'}`}>
                      {previewTime}
                    </span>
                    {hasUnread ? (
                      <span className="w-2 h-2 rounded-full bg-indigo-500 flex-shrink-0" aria-label="Unread" />
                    ) : (
                      <span className="w-2 h-2 flex-shrink-0" aria-hidden="true" />
                    )}
                  </div>
                </div>

                {onDeleteConversation && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onDeleteConversation(conv.id);
                    }}
                    className="opacity-0 group-hover:opacity-100 p-1.5 text-slate-400 hover:text-rose-400 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-all flex-shrink-0"
                    title="Delete conversation"
                    aria-label="Delete conversation"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
