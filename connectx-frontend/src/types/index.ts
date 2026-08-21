export type ProfilePhotoVisibility = 'EVERYONE' | 'CONNECTIONS';

// "Who can add me to a group?" -- account-level privacy, not per-group. Absent/undefined means
// ANYONE (mirrors the backend's null-means-ANYONE convention for this exact field).
export type GroupAddPrivacy = 'ANYONE' | 'CONNECTIONS' | 'NOBODY';

export interface User {
  id: number;
  username: string;
  // Only present for the current user's own profile (GET /users/me, auth responses). Every
  // "another user" view -- GET /users/{id}, search, conversation member lists -- omits it
  // server-side entirely; never assume it's populated outside a self-profile context.
  email?: string;
  displayName: string;
  profileImageUrl?: string;
  status: 'ONLINE' | 'OFFLINE' | 'AWAY';
  lastSeenAt?: string;
  createdAt: string;
  // Absent/undefined on users created before this setting existed -- treat the same as EVERYONE.
  profilePhotoVisibility?: ProfilePhotoVisibility;
  groupAddPrivacy?: GroupAddPrivacy;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  user: User;
}

export interface Device {
  id: number;
  userId: number;
  deviceName: string;
  publicKey: string;
  keyAlgorithm: string;
  createdAt: string;
  lastSeenAt: string;
  active: boolean;
}

export interface UserPublicKey {
  deviceId: number;
  userId: number;
  deviceName: string;
  publicKey: string;
  keyAlgorithm: string;
}

export interface MessageReaction {
  id?: number;
  messageId: number;
  userId: number;
  username: string;
  reaction: string;
  createdAt: string;
}

export interface ReplyTarget {
  messageId: number;
  senderUsername: string;
  messageType: MessageType;
  previewText: string;
}

// OWNER/ADMIN/MEMBER for a GROUP conversation member; null/undefined for a DIRECT one (role only
// ever applies to groups).
export type GroupRole = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface ConversationMember {
  id: number;
  user: User;
  joinedAt: string;
  lastReadMessageId?: number;
  pinned?: boolean;
  pinnedAt?: string;
  mutedUntil?: string;
  muted?: boolean;
  archived?: boolean;
  archivedAt?: string;
  manuallyMarkedUnread?: boolean;
  role?: GroupRole | null;
}

export type MessageType = 'TEXT' | 'IMAGE' | 'LOCATION' | 'DOCUMENT';

export interface Conversation {
  id: number;
  type: 'DIRECT' | 'GROUP';
  createdAt: string;
  updatedAt: string;
  members: ConversationMember[];
  lastMessageId?: number;
  lastMessageSenderUserId?: number;
  lastMessageSentAt?: string;
  lastMessageDeletedForEveryone?: boolean;
  lastMessageType?: MessageType;
  lastMessageCaption?: string;
  pinned?: boolean;
  pinnedAt?: string;
  isMuted?: boolean;
  mutedUntil?: string;
  archived?: boolean;
  archivedAt?: string;
  manuallyMarkedUnread?: boolean;
}

export interface ConversationPreview {
  messageId?: number;
  text: string;
  sentAt: string;
  senderUserId: number;
}

export interface Message {
  id: number;
  conversationId: number;
  senderUserId: number;
  senderUsername: string;
  senderDeviceId?: number;
  recipientDeviceId?: number;
  messageType?: MessageType;
  mediaId?: number;
  caption?: string;
  mimeType?: string;
  fileSizeBytes?: number;
  // GROUP TEXT/IMAGE/DOCUMENT only -- the file's own AES-GCM nonce, distinct from `nonce` above
  // (which for an encrypted GROUP image/document instead protects the optional caption). Absent
  // for DIRECT media and for GROUP media sent before the media-encryption stage.
  mediaNonce?: string;
  latitude?: number;
  longitude?: number;
  locationLabel?: string;
  encryptionAlgorithm?: string;
  ciphertext: string;
  nonce: string;
  // GROUP TEXT messages only -- the shared group key version this ciphertext was encrypted under.
  // Absent/undefined for DIRECT messages and for non-TEXT group messages.
  groupKeyVersion?: number;
  sentAt: string;
  deliveredAt?: string;
  readAt?: string;
  deletedForEveryone: boolean;
  decryptedContent?: string; // Client-side decrypted plaintext cache
  decryptionError?: boolean;
  localMediaUrl?: string; // Client-only optimistic preview URL
  replyToMessageId?: number;
  replyToSenderUsername?: string;
  replyToMessageType?: MessageType;
  replyToCaption?: string;
  replyToDeleted?: boolean;
  reactions?: MessageReaction[];
  clientTempId?: string;
  status?: 'SENDING' | 'SENT' | 'FAILED';
  editedAt?: string;
  forwarded?: boolean;
  pinnedAt?: string;
  pinnedByUserId?: number;
  pinnedByUsername?: string;
  starred?: boolean;
}

export interface PagedMessagesResponse {
  messages: Message[];
  hasMore: boolean;
  nextCursor?: number | null;
  limit: number;
}

export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

export interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
}

export interface WsEvent<T = any> {
  type:
    | 'MESSAGE_SEND'
    | 'MESSAGE_ACK'
    | 'MESSAGE_RECEIVED'
    | 'MESSAGE_DELIVERED'
    | 'MESSAGE_READ'
    | 'MESSAGE_REACTION_UPDATE'
    | 'MESSAGE_EDITED'
    | 'MESSAGE_DELETED'
    | 'MESSAGE_PINNED'
    | 'MESSAGE_UNPINNED'
    | 'READ_RECEIPT_UPDATE'
    | 'CONVERSATION_DELETED'
    | 'CONVERSATION_RESTORED'
    | 'CONVERSATION_CLEARED'
    | 'TYPING_INDICATOR'
    | 'PRESENCE_UPDATE'
    | 'GROUP_KEY_ROTATION_REQUIRED'
    | 'GROUP_INFO_UPDATED'
    | 'CONNECTION_REQUEST_RECEIVED'
    | 'CONNECTION_REQUEST_ACCEPTED'
    | 'CONNECTION_REQUEST_REJECTED'
    | 'CONNECTION_REQUEST_CANCELLED'
    | 'CONNECTION_REMOVED'
    | 'USER_BLOCKED'
    | 'USER_UNBLOCKED'
    | 'GROUP_INVITATION_RECEIVED'
    | 'GROUP_INVITATION_ACCEPTED'
    | 'GROUP_INVITATION_REJECTED'
    | 'GROUP_INVITATION_CANCELLED'
    | 'GROUP_ROLE_CHANGED'
    | 'GROUP_MEMBER_ADDED'
    | 'GROUP_MEMBER_REMOVED'
    | 'GROUP_ACCESS_REVOKED';
  requestId?: string;
  payload: T;
}

export type ConnectionStatus = 'CONNECTED' | 'CONNECTING' | 'DISCONNECTED' | 'ERROR';

export type ConnectionRequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';

export interface ConnectionRequestDto {
  id: number;
  requesterId: number;
  requesterUsername: string;
  requesterDisplayName: string;
  requesterProfileImageUrl?: string;
  recipientId: number;
  recipientUsername: string;
  recipientDisplayName: string;
  recipientProfileImageUrl?: string;
  status: ConnectionRequestStatus;
  createdAt: string;
  respondedAt?: string;
}

export interface UserConnectionDto {
  id: number;
  connectedUserId: number;
  connectedUsername: string;
  connectedDisplayName: string;
  connectedProfileImageUrl?: string;
  createdAt: string;
}

export interface UserBlockDto {
  id: number;
  blockedUserId: number;
  blockedUsername: string;
  blockedDisplayName: string;
  blockedProfileImageUrl?: string;
  createdAt: string;
}

// Derived client-side only -- never sent to or trusted in place of the backend,
// which re-enforces every one of these transitions independently (see
// ConnectionService / BlockService / ConversationService on the backend).
export type RelationshipStatus =
  | 'CONNECTED'
  | 'LEGACY_CHAT'
  | 'REQUEST_SENT'
  | 'REQUEST_RECEIVED'
  | 'BLOCKED_BY_ME'
  | 'NOT_CONNECTED';

// ── Groups ──────────────────────────────────────────────────────────────────
// Mirrors the backend's GroupDto exactly. The backend remains the sole authority on every one of
// these values (whoCanInvite/whoCanSendMessages/whoCanEditGroupInfo, currentUserRole) -- the
// frontend only ever reads them to decide what UI to show, never to make an authorization
// decision itself.
export type WhoCanInvite = 'OWNER_ADMIN_ONLY' | 'ALL_MEMBERS';
export type WhoCanSendMessages = 'EVERYONE' | 'ADMINS_ONLY';
export type WhoCanEditGroupInfo = 'OWNER_ADMIN_ONLY' | 'ALL_MEMBERS';

export interface Group {
  id: number;
  type: string;
  name: string;
  description?: string;
  avatarUrl?: string;
  whoCanInvite: WhoCanInvite;
  whoCanSendMessages: WhoCanSendMessages;
  whoCanEditGroupInfo: WhoCanEditGroupInfo;
  createdByUserId: number;
  // Absent/null if the current caller isn't an active member (shouldn't normally happen -- every
  // group fetch is itself membership-gated server-side).
  currentUserRole: GroupRole | null;
  activeMemberCount: number;
  // Authoritative current E2EE key version (backend ChatGroup.keyVersion). Compared against the
  // frontend's own cached/fetched wrapped key to detect a rotation this client hasn't caught up
  // with yet -- see crypto/groupKeyManager.ts.
  keyVersion: number;
  createdAt: string;
  updatedAt: string;
}

// Mirrors the backend's GroupMemberKeyDto exactly -- opaque wrapped key material plus who wrapped
// it (needed to re-derive the same ECDH shared secret for unwrap). Never a plaintext key.
export interface GroupMemberKeyPayload {
  groupId: number;
  keyVersion: number;
  wrappedKey: string;
  wrapNonce: string;
  wrappedByUserId: number | null;
}

export type GroupInvitationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';

export interface GroupInvitation {
  id: number;
  groupId: number;
  groupName: string;
  invitee: User;
  invitedBy: User;
  status: GroupInvitationStatus;
  createdAt: string;
  respondedAt?: string;
}

// "DIRECT_ADDED" = the target was added immediately (invitation is null); "INVITATION_SENT" = a
// PENDING invitation was created instead (invitation is populated). A denied outcome never
// reaches this shape -- it surfaces as a thrown ApiRequestError instead.
export type CreateGroupInvitationOutcome = 'DIRECT_ADDED' | 'INVITATION_SENT';

export interface CreateGroupInvitationResult {
  outcome: CreateGroupInvitationOutcome;
  invitation: GroupInvitation | null;
}
