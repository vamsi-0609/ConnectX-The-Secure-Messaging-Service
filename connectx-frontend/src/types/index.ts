export type ProfilePhotoVisibility = 'EVERYONE' | 'CONNECTIONS';

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
  latitude?: number;
  longitude?: number;
  locationLabel?: string;
  encryptionAlgorithm?: string;
  ciphertext: string;
  nonce: string;
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
    | 'PRESENCE_UPDATE';
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
