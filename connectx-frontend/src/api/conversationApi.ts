import { apiRequest } from './apiClient';
import { Conversation } from '../types';

export const conversationApi = {
  getConversations: () =>
    apiRequest<Conversation[]>('/conversations'),

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
};
