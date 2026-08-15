import { apiRequest } from './apiClient';
import { Message, MessageType } from '../types';

export const messageApi = {
  getMessages: (conversationId: number) =>
    apiRequest<Message[]>(`/conversations/${conversationId}/messages`),

  sendMessage: (data: {
    conversationId: number;
    messageType?: MessageType;
    mediaId?: number;
    caption?: string;
    latitude?: number;
    longitude?: number;
    locationLabel?: string;
    senderDeviceId?: number;
    recipientDeviceId?: number;
    encryptionAlgorithm?: string;
    ciphertext?: string;
    nonce?: string;
    replyToMessageId?: number;
  }) =>
    apiRequest<Message>('/messages', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  deleteMessage: (messageId: number, deleteForEveryone: boolean = false) =>
    apiRequest<string>(`/messages/${messageId}?deleteForEveryone=${deleteForEveryone}`, {
      method: 'DELETE',
    }),

  addReaction: (messageId: number, reaction: string) =>
    apiRequest<Message>(`/messages/${messageId}/reactions`, {
      method: 'POST',
      body: JSON.stringify({ reaction }),
    }),

  removeReaction: (messageId: number) =>
    apiRequest<Message>(`/messages/${messageId}/reactions`, {
      method: 'DELETE',
    }),
};
