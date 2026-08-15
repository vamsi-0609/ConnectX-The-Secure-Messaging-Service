// Persists the non-sensitive conversation list to localStorage so a reload can
// paint the sidebar instantly from cache instead of showing a blank/empty state
// while `GET /conversations` is in flight. Only server-visible metadata is
// stored here — never decrypted message text (see ConversationPreview.text)
// or ciphertext. Fields are whitelisted explicitly so this stays true even if
// the `Conversation` type grows new fields later.
import { Conversation, ConversationMember, User } from '../types';

const CACHE_PREFIX = 'connectx_conv_list_cache_v1_';
const CACHE_VERSION = 1;
const MAX_CACHED_CONVERSATIONS = 150;

interface CachedPayload {
  version: number;
  savedAt: number;
  conversations: Conversation[];
}

function sanitizeUser(user: User): User {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    displayName: user.displayName,
    profileImageUrl: user.profileImageUrl,
    status: user.status,
    lastSeenAt: user.lastSeenAt,
    createdAt: user.createdAt,
  };
}

function sanitizeMember(member: ConversationMember): ConversationMember {
  return {
    id: member.id,
    user: sanitizeUser(member.user),
    joinedAt: member.joinedAt,
    lastReadMessageId: member.lastReadMessageId,
    pinned: member.pinned,
    pinnedAt: member.pinnedAt,
    mutedUntil: member.mutedUntil,
    muted: member.muted,
  };
}

// Whitelist only — deliberately excludes anything resembling decrypted text,
// ciphertext, or nonces (none of which exist on `Conversation` today, but this
// keeps the cache safe by construction rather than by convention).
function sanitizeConversation(conv: Conversation): Conversation {
  return {
    id: conv.id,
    type: conv.type,
    createdAt: conv.createdAt,
    updatedAt: conv.updatedAt,
    members: (conv.members || []).map(sanitizeMember),
    lastMessageId: conv.lastMessageId,
    lastMessageSenderUserId: conv.lastMessageSenderUserId,
    lastMessageSentAt: conv.lastMessageSentAt,
    lastMessageDeletedForEveryone: conv.lastMessageDeletedForEveryone,
    lastMessageType: conv.lastMessageType,
    lastMessageCaption: conv.lastMessageCaption,
    pinned: conv.pinned,
    pinnedAt: conv.pinnedAt,
    isMuted: conv.isMuted,
    mutedUntil: conv.mutedUntil,
  };
}

function sortByRecency(conversations: Conversation[]): Conversation[] {
  return [...conversations].sort((a, b) => {
    const at = new Date(a.lastMessageSentAt || a.updatedAt).getTime() || 0;
    const bt = new Date(b.lastMessageSentAt || b.updatedAt).getTime() || 0;
    return bt - at;
  });
}

export function loadCachedConversations(userId: number): Conversation[] {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + userId);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as CachedPayload;
    if (!parsed || parsed.version !== CACHE_VERSION || !Array.isArray(parsed.conversations)) {
      return [];
    }
    return parsed.conversations;
  } catch {
    return [];
  }
}

export function saveCachedConversations(userId: number, conversations: Conversation[]): void {
  try {
    const sanitized = sortByRecency(conversations)
      .slice(0, MAX_CACHED_CONVERSATIONS)
      .map(sanitizeConversation);

    const payload: CachedPayload = {
      version: CACHE_VERSION,
      savedAt: Date.now(),
      conversations: sanitized,
    };

    localStorage.setItem(CACHE_PREFIX + userId, JSON.stringify(payload));
  } catch {
    // Storage full/unavailable (e.g. private browsing) — cache is best-effort only.
  }
}

// Removes every cached conversation list for every user this browser has ever
// logged in as. Called on logout/auth-expiry so a shared machine never shows
// one account's chat list to the next.
export function clearCachedConversationLists(): void {
  try {
    const keysToRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(CACHE_PREFIX)) {
        keysToRemove.push(key);
      }
    }
    keysToRemove.forEach((key) => localStorage.removeItem(key));
  } catch {
    // ignore
  }
}
