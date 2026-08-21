import React, { useState } from 'react';
import {
  Search,
  MessageSquarePlus,
  Users,
  UserPlus,
  Pin,
  PinOff,
  BellOff,
  Archive,
  ArchiveRestore,
  Mail,
  MailOpen,
  Trash2,
  MoreVertical,
  Lock,
} from 'lucide-react';
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
    <div className="w-full md:w-[320px] lg:w-[340px] xl:w-[360px] h-full bg-white dark:bg-[#0a0e1a] border-r border-slate-200/90 dark:border-slate-800/80 flex flex-col flex-shrink-0 select-none">
      {/* ── Header ─────────────────────────────── */}
      <div className="px-3.5 sm:px-4 pt-3.5 pb-3 md:pt-4 md:pb-3.5 border-b border-slate-200/90 dark:border-slate-800/80 space-y-3 bg-slate-50/80 dark:bg-slate-950/40 flex-shrink-0">
        <div className="flex items-center justify-between gap-2">
          {/* Brand Identity */}
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="flex items-center justify-center flex-shrink-0">
              <ConnectXLogo size="md" variant="gradient" static />
            </div>
            <div className="min-w-0">
              <h1 className="text-base md:text-[17px] font-extrabold text-slate-900 dark:text-white leading-none tracking-tight">
                Connect<span className="text-violet-600 dark:text-violet-400">X</span>
              </h1>
              <p className="text-[10px] font-semibold tracking-wider text-violet-500 dark:text-violet-400 uppercase mt-0.5">
                Encrypted
              </p>
            </div>
          </div>

          {/* Action Icons (Friend requests, Group invitations, Compose, Profile) */}
          <div className="flex items-center gap-1 sm:gap-1.5 flex-shrink-0">
            {/* 1. Connection / Friend Requests */}
            {onOpenConnectionRequests && (
              <button
                onClick={onOpenConnectionRequests}
                className="group relative w-9 h-9 flex items-center justify-center text-slate-600 dark:text-slate-300 bg-slate-100/90 dark:bg-slate-800/70 hover:bg-slate-200/80 dark:hover:bg-slate-700/80 hover:text-slate-900 dark:hover:text-white active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-500/50 rounded-xl transition-all duration-150 ease-out motion-reduce:transition-none cursor-pointer"
                title="Connection requests"
                aria-label="Connection requests"
              >
                <UserPlus className="w-4 h-4 transition-transform duration-150 group-hover:scale-105" />
                {pendingConnectionRequestCount > 0 && (
                  <span className="absolute -top-1 -right-1 min-w-[17px] h-[17px] px-1 flex items-center justify-center text-[10px] font-bold text-white bg-violet-600 rounded-full shadow-xs">
                    {pendingConnectionRequestCount > 9 ? '9+' : pendingConnectionRequestCount}
                  </span>
                )}
              </button>
            )}

            {/* 2. Group Invitations */}
            {onOpenGroupInvitations && (
              <button
                onClick={onOpenGroupInvitations}
                className="group relative w-9 h-9 flex items-center justify-center text-slate-600 dark:text-slate-300 bg-slate-100/90 dark:bg-slate-800/70 hover:bg-slate-200/80 dark:hover:bg-slate-700/80 hover:text-slate-900 dark:hover:text-white active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-500/50 rounded-xl transition-all duration-150 ease-out motion-reduce:transition-none cursor-pointer"
                title="Group invitations"
                aria-label="Group invitations"
              >
                <Users className="w-4 h-4 transition-transform duration-150 group-hover:scale-105" />
                {pendingGroupInvitationCount > 0 && (
                  <span className="absolute -top-1 -right-1 min-w-[17px] h-[17px] px-1 flex items-center justify-center text-[10px] font-bold text-white bg-violet-600 rounded-full shadow-xs">
                    {pendingGroupInvitationCount > 9 ? '9+' : pendingGroupInvitationCount}
                  </span>
                )}
              </button>
            )}

            {/* 3. Hero Compose Action: MessageSquarePlus with refined lift, glow & compression */}
            <div className="relative">
              <button
                onClick={() => (onOpenCreateGroup ? setShowNewMenu((v) => !v) : onOpenSearch())}
                className="group/compose relative w-9 h-9 flex items-center justify-center text-white bg-violet-600 dark:bg-violet-600 hover:bg-violet-500 dark:hover:bg-violet-500 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.96] rounded-xl shadow-sm hover:shadow-md hover:shadow-violet-600/30 transition-all duration-200 ease-out motion-reduce:transform-none motion-reduce:transition-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-500/50 cursor-pointer overflow-hidden"
                title="Start a new conversation or group"
                aria-label="Start a new conversation or group"
              >
                {/* Micro hover aura */}
                <span className="absolute inset-0 bg-gradient-to-tr from-white/0 via-white/15 to-white/0 opacity-0 group-hover/compose:opacity-100 transition-opacity duration-200 pointer-events-none" />
                <MessageSquarePlus className="w-4 h-4 relative z-10 transition-transform duration-200 group-hover/compose:scale-105" />
              </button>

              {showNewMenu && onOpenCreateGroup && (
                <>
                  <div className="fixed inset-0 z-20" onClick={() => setShowNewMenu(false)} />
                  <div className="absolute right-0 top-full mt-1.5 w-52 bg-white/95 dark:bg-[#0c101c]/95 border border-slate-200/90 dark:border-slate-800/90 rounded-xl shadow-xl z-30 py-1.5 text-xs sm:text-sm animate-pop-in backdrop-blur-sm">
                    <button
                      onClick={() => {
                        setShowNewMenu(false);
                        onOpenSearch();
                      }}
                      className="w-full text-left px-3.5 py-2.5 flex items-center gap-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 cursor-pointer font-medium transition-colors"
                    >
                      <MessageSquarePlus className="w-4 h-4 text-violet-600 dark:text-violet-400" />
                      <span>New chat</span>
                    </button>
                    <button
                      onClick={() => {
                        setShowNewMenu(false);
                        onOpenCreateGroup();
                      }}
                      className="w-full text-left px-3.5 py-2.5 flex items-center gap-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 cursor-pointer font-medium transition-colors"
                    >
                      <Users className="w-4 h-4 text-violet-600 dark:text-violet-400" />
                      <span>New group</span>
                    </button>
                  </div>
                </>
              )}
            </div>

            {/* 4. Profile / Avatar Action */}
            {onOpenProfile && (
              <button
                onClick={onOpenProfile}
                className="w-9 h-9 flex items-center justify-center rounded-xl hover:ring-2 hover:ring-violet-500/40 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-500/50 transition-all duration-150 ease-out motion-reduce:transition-none cursor-pointer overflow-hidden flex-shrink-0"
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
          <Search className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400 dark:text-slate-500 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search conversations..."
            className="w-full pl-9 pr-3.5 py-2 sm:py-2.5 bg-white dark:bg-slate-900/70 border border-slate-200/90 dark:border-slate-800/80 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500/60 focus:ring-2 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
          />
        </div>

        {/* List Header Count & Archived Toggle */}
        <div className="flex items-center justify-between gap-2 px-0.5">
          <span className="text-[11px] font-medium text-slate-400 dark:text-slate-500">
            {filteredConversations.length} {showArchived ? 'archived' : 'chats'}
          </span>
          {(archivedCount > 0 || showArchived) && (
            <button
              onClick={() => setShowArchived((prev) => !prev)}
              className="flex items-center gap-1 text-[11px] font-medium text-violet-600 dark:text-violet-400 hover:text-violet-700 dark:hover:text-violet-300 transition-colors cursor-pointer"
            >
              {showArchived ? (
                <>
                  <ArchiveRestore className="w-3.5 h-3.5" />
                  <span>Back to chats</span>
                </>
              ) : (
                <>
                  <Archive className="w-3.5 h-3.5" />
                  <span>Archived ({archivedCount})</span>
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {/* ── Conversation List ────────────────────── */}
      <div className="flex-1 overflow-y-auto divide-y divide-slate-100/90 dark:divide-slate-800/40">
        {filteredConversations.length === 0 ? (
          <div className="text-center py-16 px-6 space-y-3">
            <div className="w-12 h-12 rounded-full bg-violet-500/10 flex items-center justify-center text-violet-500 mx-auto">
              {showArchived ? <Archive className="w-5 h-5" /> : <Lock className="w-5 h-5" />}
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400">
              {showArchived ? 'No archived chats' : 'No conversations found'}
            </p>
            {!showArchived && (
              <button
                onClick={onOpenSearch}
                className="text-sm text-violet-600 dark:text-violet-400 hover:underline font-medium cursor-pointer"
              >
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

            const isSelf = listMeta.previewText.startsWith('You: ');
            const cleanPreview = isSelf ? listMeta.previewText.slice(5) : listMeta.previewText;

            return (
              <div
                key={conv.id}
                onClick={() => onSelectConversation(conv)}
                className={`group/row relative flex items-center gap-3 pl-3.5 pr-2.5 py-3 md:min-h-[70px] cursor-pointer transition-colors duration-150 ${
                  isActive
                    ? 'bg-violet-500/10 dark:bg-violet-500/15'
                    : isPinned
                    ? 'bg-slate-50/70 dark:bg-slate-900/40'
                    : 'hover:bg-slate-50/90 dark:hover:bg-slate-800/30 active:bg-slate-100/80 dark:active:bg-slate-800/50'
                }`}
              >
                {/* Active indicator bar */}
                {isActive && (
                  <span className="absolute left-0 top-2 bottom-2 w-[3px] bg-violet-600 dark:bg-violet-500 rounded-r-full" />
                )}

                {/* Avatar with Group badge if applicable */}
                <div className="relative flex-shrink-0">
                  {isGroup ? (
                    <>
                      <GroupAvatar name={rowName} avatarUrl={group?.avatarUrl} size="md" />
                      <span
                        className="absolute -bottom-0.5 -right-0.5 w-4 h-4 rounded-full bg-violet-600 text-white flex items-center justify-center ring-2 ring-white dark:ring-[#0a0e1a] shadow-xs"
                        title="Group conversation"
                      >
                        <Users className="w-2.5 h-2.5" />
                      </span>
                    </>
                  ) : (
                    <UserAvatar user={recipient!} size="md" passive />
                  )}
                </div>

                {/* Middle content: Name row + Preview row */}
                <div className="flex-1 min-w-0 flex flex-col justify-center gap-0.5">
                  {/* Name Row */}
                  <div className="flex items-center gap-1.5 min-w-0 pr-1">
                    <h3
                      className={`text-sm md:text-[14.5px] truncate leading-snug ${
                        hasUnread ? 'font-bold text-slate-950 dark:text-white' : 'font-semibold text-slate-800 dark:text-slate-200'
                      }`}
                    >
                      {rowName}
                    </h3>
                    {isPinned && (
                      <span className="inline-flex items-center text-violet-500 dark:text-violet-400 flex-shrink-0" title="Pinned chat">
                        <Pin className="w-3 h-3 fill-violet-500/20" />
                      </span>
                    )}
                    {isMuted && (
                      <span className="inline-flex items-center text-slate-400 dark:text-slate-500 flex-shrink-0" title="Muted chat">
                        <BellOff className="w-3 h-3" />
                      </span>
                    )}
                  </div>

                  {/* Preview Text with quiet neutral 'You:' prefix */}
                  <p
                    className={`text-xs md:text-[13px] truncate leading-normal ${
                      hasUnread ? 'text-slate-800 dark:text-slate-200 font-medium' : 'text-slate-500 dark:text-slate-400'
                    }`}
                  >
                    {isSelf && (
                      <span className="text-slate-500 dark:text-slate-400 font-medium mr-1 select-none">
                        You:
                      </span>
                    )}
                    <span>{cleanPreview}</span>
                  </p>
                </div>

                {/* Stable Right Column: Timestamp on top, Unread Badge + ⋮ Menu on bottom */}
                <div className="flex-shrink-0 flex flex-col items-end justify-center gap-1 w-20 min-w-[80px]">
                  {/* Timestamp */}
                  <span
                    className={`text-[11px] font-medium whitespace-nowrap leading-none text-right ${
                      hasUnread ? 'text-violet-600 dark:text-violet-400 font-semibold' : 'text-slate-400 dark:text-slate-500'
                    }`}
                  >
                    {previewTime}
                  </span>

                  {/* Unread indicator + More Menu */}
                  <div className="flex items-center justify-end gap-1.5 h-6">
                    {hasUnread && (
                      <span
                        className="w-2 h-2 rounded-full bg-violet-600 dark:bg-violet-500 flex-shrink-0 shadow-xs"
                        aria-label="Unread"
                      />
                    )}

                    <div className="relative">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setOpenMenuId((prev) => (prev === conv.id ? null : conv.id));
                        }}
                        className={`w-6 h-6 flex items-center justify-center rounded-md transition-colors cursor-pointer ${
                          openMenuId === conv.id
                            ? 'text-slate-800 dark:text-white bg-slate-200/80 dark:bg-slate-800'
                            : 'text-slate-400 dark:text-slate-500 group-hover/row:text-slate-600 dark:group-hover/row:text-slate-300 hover:!text-slate-900 dark:hover:!text-white hover:bg-slate-100 dark:hover:bg-slate-800/70'
                        }`}
                        title="Chat options"
                        aria-label="Chat options"
                      >
                        <MoreVertical className="w-3.5 h-3.5" />
                      </button>

                      {/* Dropdown Menu */}
                      {openMenuId === conv.id && (
                        <>
                          <div
                            className="fixed inset-0 z-20"
                            onClick={(e) => {
                              e.stopPropagation();
                              setOpenMenuId(null);
                            }}
                          />
                          <div
                            onClick={(e) => e.stopPropagation()}
                            className="absolute right-0 top-full mt-1 w-48 bg-white/95 dark:bg-[#0c101c]/95 border border-slate-200/90 dark:border-slate-800/90 rounded-xl shadow-xl z-30 py-1.5 text-xs sm:text-sm animate-pop-in backdrop-blur-sm"
                          >
                            {isPinned ? (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  onUnpinConversation?.(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-violet-600 dark:text-violet-400 font-medium cursor-pointer transition-colors"
                              >
                                <PinOff className="w-4 h-4" /> Unpin chat
                              </button>
                            ) : (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  if (pinnedCount >= 2) {
                                    alert('You can pin up to 2 chats.');
                                    return;
                                  }
                                  onPinConversation?.(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 cursor-pointer transition-colors"
                              >
                                <Pin className="w-4 h-4" /> Pin chat
                              </button>
                            )}

                            {hasUnread ? (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  onMarkRead?.(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-violet-600 dark:text-violet-400 font-medium cursor-pointer transition-colors"
                              >
                                <MailOpen className="w-4 h-4" /> Mark as read
                              </button>
                            ) : (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  onMarkUnread?.(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 cursor-pointer transition-colors"
                              >
                                <Mail className="w-4 h-4" /> Mark as unread
                              </button>
                            )}

                            {isArchivedConv ? (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  onUnarchiveConversation?.(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-violet-600 dark:text-violet-400 font-medium cursor-pointer transition-colors"
                              >
                                <ArchiveRestore className="w-4 h-4" /> Unarchive chat
                              </button>
                            ) : (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  onArchiveConversation?.(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-slate-50 dark:hover:bg-slate-800/70 text-slate-700 dark:text-slate-200 cursor-pointer transition-colors"
                              >
                                <Archive className="w-4 h-4" /> Archive chat
                              </button>
                            )}

                            {onDeleteConversation && (
                              <button
                                onClick={() => {
                                  setOpenMenuId(null);
                                  onDeleteConversation(conv.id);
                                }}
                                className="w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-rose-50 dark:hover:bg-rose-950/30 text-rose-600 dark:text-rose-400 cursor-pointer transition-colors"
                              >
                                <Trash2 className="w-4 h-4" /> Delete conversation
                              </button>
                            )}
                          </div>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
