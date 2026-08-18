import React, { useState } from 'react';
import { Search, Plus, Lock, Trash2, Pin, PinOff, BellOff, Archive, ArchiveRestore, MailOpen, Mail, MoreVertical, UserPlus, Users, MessageCirclePlus } from 'lucide-react';
import { User, Conversation, ConversationPreview, Group } from '../../types';
import { formatConversationTime } from '../../utils/messageGroups';
import {
  getConversationListMeta,
  isConversationPinned,
  isConversationArchived,
  isConversationManuallyUnread,
} from '../../utils/conversationList';
import { UserAvatar } from '../common/UserAvatar';
import { ConnectXLogo } from '../common/ConnectXLogo';
import { GroupAvatar } from '../group/GroupAvatar';

interface ChatListSidebarProps {
  currentUser: User;
  conversations: Conversation[];
  activeConversationId: number | null;
  unreadConversationIds?: Set<number>;
  conversationPreviews: Record<number, ConversationPreview>;
  // GROUP conversations carry no name/avatar on the conversation list response itself (see
  // groupInfoById's App.tsx-side loader) -- looked up here by conversation id, best-effort ("Group"
  // is shown until it resolves).
  groupInfoById?: Record<number, Group>;
  onSelectConversation: (conv: Conversation) => void;
  onOpenSearch: () => void;
  onOpenCreateGroup?: () => void;
  onOpenProfile?: () => void;
  onOpenConnectionRequests?: () => void;
  pendingConnectionRequestCount?: number;
  onOpenGroupInvitations?: () => void;
  pendingGroupInvitationCount?: number;
  onDeleteConversation?: (conversationId: number) => void;
  onPinConversation?: (conversationId: number) => void;
  onUnpinConversation?: (conversationId: number) => void;
  onArchiveConversation?: (conversationId: number) => void;
  onUnarchiveConversation?: (conversationId: number) => void;
  onMarkUnread?: (conversationId: number) => void;
  onMarkRead?: (conversationId: number) => void;
}

export const ChatListSidebar: React.FC<ChatListSidebarProps> = ({
  currentUser,
  conversations,
  activeConversationId,
  unreadConversationIds,
  conversationPreviews,
  groupInfoById = {},
  onSelectConversation,
  onOpenSearch,
  onOpenCreateGroup,
  onOpenProfile,
  onOpenConnectionRequests,
  pendingConnectionRequestCount = 0,
  onOpenGroupInvitations,
  pendingGroupInvitationCount = 0,
  onDeleteConversation,
  onPinConversation,
  onUnpinConversation,
  onArchiveConversation,
  onUnarchiveConversation,
  onMarkUnread,
  onMarkRead,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [openMenuId, setOpenMenuId] = useState<number | null>(null);
  const [showNewMenu, setShowNewMenu] = useState(false);

  const getRecipient = (conv: Conversation): User | null => {
    if (!conv.members?.length) return null;
    const member = conv.members.find(
      (m) => m.user?.id != null && Number(m.user.id) !== Number(currentUser.id)
    );
    return member?.user ?? null;
  };

  const pinnedCount = conversations.filter((c) => isConversationPinned(c, currentUser.id)).length;
  const archivedCount = conversations.filter((c) => isConversationArchived(c, currentUser.id)).length;

  const filteredConversations = conversations
    .filter((conv) => {
      const isArchived = isConversationArchived(conv, currentUser.id);
      if (showArchived !== isArchived) return false;

      if (conv.type === 'GROUP') {
        const groupName = groupInfoById[conv.id]?.name || '';
        return groupName.toLowerCase().includes(searchQuery.toLowerCase()) || !searchQuery.trim();
      }

      const recipient = getRecipient(conv);
      if (!recipient) return false;

      const matchesSearch =
        recipient.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (recipient.displayName && recipient.displayName.toLowerCase().includes(searchQuery.toLowerCase()));

      return matchesSearch;
    })
    .sort((a, b) => {
      const aPinned = isConversationPinned(a, currentUser.id);
      const bPinned = isConversationPinned(b, currentUser.id);

      if (aPinned && !bPinned) return -1;
      if (!aPinned && bPinned) return 1;

      const aMeta = getConversationListMeta(a, conversationPreviews[a.id], currentUser.id);
      const bMeta = getConversationListMeta(b, conversationPreviews[b.id], currentUser.id);
      return bMeta.sortTime - aMeta.sortTime;
    });

  return (
    <div className="w-full md:w-[320px] lg:w-[340px] xl:w-[360px] h-full bg-white dark:bg-[#0f172a] border-r border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0 select-none">
      {/* ── Header ─────────────────────────────── */}
      <div className="px-4 pt-4 pb-3 md:pt-5 md:pb-4 border-b border-slate-200 dark:border-slate-800/80 space-y-3 bg-slate-50/90 dark:bg-slate-950/50">
        <div className="flex items-center justify-between gap-2">

          {/* Brand identity */}
          <div className="flex items-center gap-2.5 min-w-0">
            {/* Canonical ConnectX shield — exact brand logo */}
            <div className="flex items-center justify-center flex-shrink-0">
              <ConnectXLogo size="md" variant="gradient" />
            </div>
            <div className="min-w-0">
              <h1 className="text-base md:text-[17px] font-bold text-slate-900 dark:text-white leading-none">
                Connect<span className="text-indigo-500">X</span>
              </h1>
              <p className="text-[10px] font-semibold tracking-wider text-indigo-400 uppercase mt-0.5">E2EE Secured</p>
            </div>
          </div>

          {/* Header actions — both buttons identical: w-9 h-9 rounded-xl */}
          <div className="flex items-center gap-1.5 flex-shrink-0">
            {onOpenConnectionRequests && (
              <button
                onClick={onOpenConnectionRequests}
                className="relative w-9 h-9 flex items-center justify-center text-slate-500 dark:text-slate-300 bg-slate-100 dark:bg-slate-800/80 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-xl transition-colors"
                title="Connection requests"
                aria-label="Connection requests"
              >
                <UserPlus className="w-4 h-4" />
                {pendingConnectionRequestCount > 0 && (
                  <span className="absolute -top-1 -right-1 min-w-[16px] h-4 px-1 flex items-center justify-center text-[10px] font-bold text-white bg-red-500 rounded-full">
                    {pendingConnectionRequestCount > 9 ? '9+' : pendingConnectionRequestCount}
                  </span>
                )}
              </button>
            )}

            {onOpenGroupInvitations && (
              <button
                onClick={onOpenGroupInvitations}
                className="relative w-9 h-9 flex items-center justify-center text-slate-500 dark:text-slate-300 bg-slate-100 dark:bg-slate-800/80 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-xl transition-colors"
                title="Group invitations"
                aria-label="Group invitations"
              >
                <Users className="w-4 h-4" />
                {pendingGroupInvitationCount > 0 && (
                  <span className="absolute -top-1 -right-1 min-w-[16px] h-4 px-1 flex items-center justify-center text-[10px] font-bold text-white bg-red-500 rounded-full">
                    {pendingGroupInvitationCount > 9 ? '9+' : pendingGroupInvitationCount}
                  </span>
                )}
              </button>
            )}

            <div className="relative">
              <button
                onClick={() => (onOpenCreateGroup ? setShowNewMenu((v) => !v) : onOpenSearch())}
                className="w-9 h-9 flex items-center justify-center text-white bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 rounded-xl transition-colors shadow-sm"
                title="New chat or group"
                aria-label="New chat or group"
              >
                <Plus className="w-4 h-4" />
              </button>
              {showNewMenu && onOpenCreateGroup && (
                <>
                  <div className="fixed inset-0 z-20" onClick={() => setShowNewMenu(false)} />
                  <div className="absolute right-0 top-full mt-1 w-52 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-30 py-1 text-sm">
                    <button
                      onClick={() => {
                        setShowNewMenu(false);
                        onOpenSearch();
                      }}
                      className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
                    >
                      <MessageCirclePlus className="w-4 h-4" /> New chat
                    </button>
                    <button
                      onClick={() => {
                        setShowNewMenu(false);
                        onOpenCreateGroup();
                      }}
                      className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
                    >
                      <Users className="w-4 h-4" /> New group
                    </button>
                  </div>
                </>
              )}
            </div>

            {onOpenProfile && (
              <button
                onClick={onOpenProfile}
                className="w-9 h-9 flex items-center justify-center rounded-xl hover:opacity-80 active:opacity-70 transition-opacity overflow-hidden"
                title="Profile & settings"
                aria-label="Open profile menu"
              >
                <UserAvatar user={currentUser} size="sm" className="rounded-xl" passive />
              </button>
            )}
          </div>
        </div>

        {/* Search */}
        <div className="relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search conversations..."
            className="w-full pl-9 pr-3 py-2 md:py-2.5 bg-white dark:bg-slate-900/80 border border-slate-200 dark:border-slate-800 rounded-xl text-sm md:text-[14px] text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500/50 transition-colors select-text"
          />
        </div>

        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] text-slate-400">
            {filteredConversations.length} {showArchived ? 'archived' : 'chats'}
          </span>
          {(archivedCount > 0 || showArchived) && (
            <button
              onClick={() => setShowArchived((prev) => !prev)}
              className="flex items-center gap-1 text-[11px] font-medium text-indigo-500 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
            >
              {showArchived ? (
                <>
                  <ArchiveRestore className="w-3 h-3" />
                  <span>Back to chats</span>
                </>
              ) : (
                <>
                  <Archive className="w-3 h-3" />
                  <span>Archived ({archivedCount})</span>
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {/* ── Conversation list ────────────────────── */}
      <div className="flex-1 overflow-y-auto">
        {filteredConversations.length === 0 ? (
          <div className="text-center py-16 px-6 space-y-3">
            <div className="w-12 h-12 rounded-full bg-indigo-600/10 flex items-center justify-center text-indigo-400 mx-auto">
              {showArchived ? <Archive className="w-5 h-5" /> : <Lock className="w-5 h-5" />}
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400">
              {showArchived ? 'No archived chats' : 'No conversations found'}
            </p>
            {!showArchived && (
              <button onClick={onOpenSearch} className="text-sm text-indigo-500 hover:underline font-medium">
                Start an encrypted chat
              </button>
            )}
          </div>
        ) : (
          filteredConversations.map((conv) => {
            const isGroup = conv.type === 'GROUP';
            const recipient = isGroup ? null : getRecipient(conv);
            if (!isGroup && !recipient) return null;
            const group = isGroup ? groupInfoById[conv.id] : undefined;
            const rowName = isGroup ? group?.name || 'Group' : recipient!.displayName || recipient!.username;
            const isActive = conv.id === activeConversationId;
            const hasUnread =
              unreadConversationIds?.has(conv.id) || isConversationManuallyUnread(conv, currentUser.id);
            const isPinned = isConversationPinned(conv, currentUser.id);
            const isArchivedConv = isConversationArchived(conv, currentUser.id);
            const isMuted = conv.isMuted || (conv.mutedUntil && new Date(conv.mutedUntil).getTime() > Date.now());
            const listMeta = getConversationListMeta(conv, conversationPreviews[conv.id], currentUser.id);
            const previewTime = formatConversationTime(listMeta.previewSentAt);

            return (
              <div
                key={conv.id}
                onClick={() => onSelectConversation(conv)}
                className={`group relative flex items-center gap-3 pl-4 pr-3 py-3 md:min-h-[72px] md:py-3.5 cursor-pointer border-b border-slate-100 dark:border-slate-800/50 transition-colors ${
                  isActive
                    ? 'bg-indigo-600/10 dark:bg-indigo-600/15'
                    : isPinned
                    ? 'bg-slate-50/70 dark:bg-slate-900/60'
                    : 'hover:bg-slate-50 dark:hover:bg-slate-800/40'
                }`}
              >
                {/* Active indicator stripe */}
                {isActive && (
                  <span className="absolute left-0 top-0 bottom-0 w-[3px] bg-indigo-500 rounded-r-full" />
                )}

                {isGroup ? (
                  <GroupAvatar name={rowName} avatarUrl={group?.avatarUrl} size="md" />
                ) : (
                  <UserAvatar user={recipient!} size="md" passive />
                )}

                {/*
                  Layout: [Name on left, Timestamp anchored to rightmost edge]
                  [Message preview on left, Unread dot on right]
                */}
                <div className="flex-1 min-w-0 flex flex-col justify-center gap-1">
                  {/* Top row: Name & Pin (left) + Timestamp (true rightmost) */}
                  <div className="flex items-center justify-between gap-2 min-w-0">
                    <div className="flex items-center gap-1.5 min-w-0">
                      <h3
                        className={`text-sm md:text-[15px] font-semibold truncate ${
                          hasUnread ? 'text-slate-900 dark:text-white' : 'text-slate-800 dark:text-slate-100'
                        }`}
                      >
                        {rowName}
                      </h3>
                      {isPinned && (
                        <span className="inline-flex items-center p-0.5 text-indigo-500 dark:text-indigo-400 flex-shrink-0" title="Pinned chat">
                          <Pin className="w-3 h-3 fill-indigo-500/20" />
                        </span>
                      )}
                      {isMuted && (
                        <span className="inline-flex items-center p-0.5 text-slate-400 dark:text-slate-500 flex-shrink-0" title="Muted chat">
                          <BellOff className="w-3 h-3" />
                        </span>
                      )}
                    </div>

                    <span
                      className={`text-[11px] md:text-xs whitespace-nowrap leading-none flex-shrink-0 text-right ${
                        hasUnread ? 'text-indigo-500 font-medium' : 'text-slate-400'
                      }`}
                    >
                      {previewTime}
                    </span>
                  </div>

                  {/* Bottom row: Message preview (left) + Unread dot (right) */}
                  <div className="flex items-center justify-between gap-2 min-w-0">
                    <p
                      className={`text-xs md:text-[13px] truncate ${
                        hasUnread ? 'text-slate-700 dark:text-slate-200 font-medium' : 'text-slate-500 dark:text-slate-400'
                      }`}
                    >
                      {listMeta.previewText}
                    </p>

                    {hasUnread && (
                      <span className="w-2 h-2 rounded-full bg-indigo-500 flex-shrink-0" aria-label="Unread" />
                    )}
                  </div>
                </div>

                {/*
                  Always-visible "more" menu — not hover-gated, so it works on
                  touch devices (mobile) as well as pointer devices (desktop).
                */}
                <div className="relative flex-shrink-0 self-center">
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setOpenMenuId((prev) => (prev === conv.id ? null : conv.id));
                    }}
                    className={`p-1.5 rounded-lg transition-colors ${
                      openMenuId === conv.id
                        ? 'text-slate-700 dark:text-white bg-slate-100 dark:bg-slate-800/60'
                        : 'text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/60'
                    }`}
                    title="Chat options"
                    aria-label="Chat options"
                  >
                    <MoreVertical className="w-4 h-4" />
                  </button>

                  {openMenuId === conv.id && (
                    <>
                      <div
                        className="fixed inset-0 z-20"
                        onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); }}
                      />
                      <div
                        onClick={(e) => e.stopPropagation()}
                        className="absolute right-0 top-full mt-1 w-48 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-30 py-1 text-sm"
                      >
                        {isPinned ? (
                          <button
                            onClick={() => { setOpenMenuId(null); onUnpinConversation?.(conv.id); }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-indigo-500"
                          >
                            <PinOff className="w-4 h-4" /> Unpin chat
                          </button>
                        ) : (
                          <button
                            onClick={() => {
                              setOpenMenuId(null);
                              if (pinnedCount >= 2) { alert('You can pin up to 2 chats.'); return; }
                              onPinConversation?.(conv.id);
                            }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
                          >
                            <Pin className="w-4 h-4" /> Pin chat
                          </button>
                        )}

                        {hasUnread ? (
                          <button
                            onClick={() => { setOpenMenuId(null); onMarkRead?.(conv.id); }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-indigo-500"
                          >
                            <MailOpen className="w-4 h-4" /> Mark as read
                          </button>
                        ) : (
                          <button
                            onClick={() => { setOpenMenuId(null); onMarkUnread?.(conv.id); }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
                          >
                            <Mail className="w-4 h-4" /> Mark as unread
                          </button>
                        )}

                        {isArchivedConv ? (
                          <button
                            onClick={() => { setOpenMenuId(null); onUnarchiveConversation?.(conv.id); }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-indigo-500"
                          >
                            <ArchiveRestore className="w-4 h-4" /> Unarchive chat
                          </button>
                        ) : (
                          <button
                            onClick={() => { setOpenMenuId(null); onArchiveConversation?.(conv.id); }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-200"
                          >
                            <Archive className="w-4 h-4" /> Archive chat
                          </button>
                        )}

                        {onDeleteConversation && (
                          <button
                            onClick={() => { setOpenMenuId(null); onDeleteConversation(conv.id); }}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-2 hover:bg-red-50 dark:hover:bg-red-950/30 text-red-600 dark:text-red-400"
                          >
                            <Trash2 className="w-4 h-4" /> Delete conversation
                          </button>
                        )}
                      </div>
                    </>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
