export interface User {
  id: number;
  username: string;
  email: string;
  displayName: string;
  profileImageUrl?: string;
  status: 'ONLINE' | 'OFFLINE' | 'AWAY';
  lastSeenAt?: string;
  createdAt: string;
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

export interface ConversationMember {
  id: number;
  user: User;
  joinedAt: string;
  lastReadMessageId?: number;
  pinned?: boolean;
  pinnedAt?: string;
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
    | 'READ_RECEIPT_UPDATE'
    | 'CONVERSATION_DELETED'
    | 'CONVERSATION_RESTORED'
    | 'CONVERSATION_CLEARED';
  requestId?: string;
  payload: T;
}

export type ConnectionStatus = 'CONNECTED' | 'CONNECTING' | 'DISCONNECTED' | 'ERROR';
