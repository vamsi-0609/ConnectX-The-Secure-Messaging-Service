import { apiRequest } from './apiClient';
import { Conversation } from '../types';

let inFlightConversations: Promise<Conversation[]> | null = null;

export const conversationApi = {
  getConversations: (): Promise<Conversation[]> => {
    if (inFlightConversations) return inFlightConversations;

    inFlightConversations = apiRequest<Conversation[]>('/conversations')
      .finally(() => {
        inFlightConversations = null;
      });

    return inFlightConversations;
  },

  createDirectConversation: (userId: number) =>
    apiRequest<Conversation>('/conversations/direct', {
      method: 'POST',
      body: JSON.stringify({ userId }),
    }),

  getConversationById: (conversationId: number) =>
    apiRequest<Conversation>(`/conversations/${conversationId}`),

  deleteConversation: (conversationId: number) =>
    apiRequest<string>(`/conversations/${conversationId}`, {
      method: 'DELETE',
    }),

  clearConversation: (conversationId: number) =>
    apiRequest<string>(`/conversations/${conversationId}/clear`, {
      method: 'POST',
    }),

  pinConversation: (conversationId: number) =>
    apiRequest<Conversation>(`/conversations/${conversationId}/pin`, {
      method: 'POST',
    }),

  unpinConversation: (conversationId: number) =>
    apiRequest<Conversation>(`/conversations/${conversationId}/unpin`, {
      method: 'POST',
    }),

  muteConversation: (conversationId: number, mutedUntil?: string) =>
    apiRequest<Conversation>(`/conversations/${conversationId}/mute`, {
      method: 'POST',
      body: JSON.stringify({ mutedUntil }),
    }),

  unmuteConversation: (conversationId: number) =>
    apiRequest<Conversation>(`/conversations/${conversationId}/unmute`, {
      method: 'POST',
    }),
};
