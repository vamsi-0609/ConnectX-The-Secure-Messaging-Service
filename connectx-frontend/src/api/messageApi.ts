import { apiRequest } from './apiClient';
import { Message, MessageType, PagedMessagesResponse } from '../types';

export const messageApi = {
  getMessages: (
    conversationId: number,
    params?: { before?: number; limit?: number },
    signal?: AbortSignal
  ) => {
    const query = new URLSearchParams();
    if (params?.before != null) query.set('before', String(params.before));
    if (params?.limit != null) query.set('limit', String(params.limit));
    const qs = query.toString() ? `?${query.toString()}` : '';
    return apiRequest<PagedMessagesResponse>(`/conversations/${conversationId}/messages${qs}`, {
      signal,
    });
  },

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
    requestId?: string;
    forwarded?: boolean;
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

  editMessage: (messageId: number, ciphertext: string, nonce: string) =>
    apiRequest<Message>(`/messages/${messageId}`, {
      method: 'PUT',
      body: JSON.stringify({ ciphertext, nonce }),
    }),

  pinMessage: (messageId: number) =>
    apiRequest<Message>(`/messages/${messageId}/pin`, {
      method: 'POST',
    }),

  unpinMessage: (messageId: number) =>
    apiRequest<Message>(`/messages/${messageId}/pin`, {
      method: 'DELETE',
    }),

  getPinnedMessage: (conversationId: number) =>
    apiRequest<Message | null>(`/conversations/${conversationId}/pinned-message`),

  starMessage: (messageId: number) =>
    apiRequest<string>(`/messages/${messageId}/star`, {
      method: 'POST',
    }),

  unstarMessage: (messageId: number) =>
    apiRequest<string>(`/messages/${messageId}/star`, {
      method: 'DELETE',
    }),
};
