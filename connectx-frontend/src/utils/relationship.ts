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
export function getRelationshipStatus(targetUserId: number, ctx: RelationshipContext): RelationshipStatus {
  if (ctx.blockedUserIds.has(targetUserId)) return 'BLOCKED_BY_ME';

  const hasLegacyChat = ctx.conversations.some(
    (c) => c.type === 'DIRECT' && c.members?.some((m) => m.user?.id === targetUserId)
  );
  if (hasLegacyChat) return 'LEGACY_CHAT';

  if (ctx.connectedUserIds.has(targetUserId)) return 'CONNECTED';
  if (ctx.sentRequestsByUserId.has(targetUserId)) return 'REQUEST_SENT';
  if (ctx.receivedRequestsByUserId.has(targetUserId)) return 'REQUEST_RECEIVED';
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
