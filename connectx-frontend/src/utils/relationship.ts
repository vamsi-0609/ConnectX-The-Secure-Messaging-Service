import { Conversation, ConnectionRequestDto, RelationshipStatus } from '../types';

export interface RelationshipContext {
  conversations: Conversation[];
  connectedUserIds: Set<number>;
  blockedUserIds: Set<number>;
  sentRequestsByUserId: Map<number, ConnectionRequestDto>;
  receivedRequestsByUserId: Map<number, ConnectionRequestDto>;
}

// Purely a UX hint for which button to render -- the backend independently
// re-checks every one of these transitions and remains the sole authority.
//
// Priority: BLOCKED > CONNECTED > REQUEST_RECEIVED > REQUEST_SENT > (LEGACY_CHAT | NOT_CONNECTED).
// A past DIRECT conversation is history, not a currently-valid messaging permission -- it must
// never outrank (or hide) the *current* connection/pending-request state, which is why
// LEGACY_CHAT is checked last, only once nothing above has already answered the question.
// Removing a connection (see ConnectionService.removeConnection on the backend) drops the pair
// from `connectedUserIds` but never touches `conversations`, so a removed connection with an
// existing chat still correctly falls through to LEGACY_CHAT here, not CONNECTED.
export function getRelationshipStatus(targetUserId: number, ctx: RelationshipContext): RelationshipStatus {
  if (ctx.blockedUserIds.has(targetUserId)) return 'BLOCKED_BY_ME';
  if (ctx.connectedUserIds.has(targetUserId)) return 'CONNECTED';
  if (ctx.receivedRequestsByUserId.has(targetUserId)) return 'REQUEST_RECEIVED';
  if (ctx.sentRequestsByUserId.has(targetUserId)) return 'REQUEST_SENT';

  const hasLegacyChat = ctx.conversations.some(
    (c) => c.type === 'DIRECT' && c.members?.some((m) => m.user?.id === targetUserId)
  );
  if (hasLegacyChat) return 'LEGACY_CHAT';

  return 'NOT_CONNECTED';
}

export function findExistingDirectConversation(
  targetUserId: number,
  conversations: Conversation[]
): Conversation | null {
  return (
    conversations.find(
      (c) => c.type === 'DIRECT' && c.members?.some((m) => m.user?.id === targetUserId)
    ) ?? null
  );
}
