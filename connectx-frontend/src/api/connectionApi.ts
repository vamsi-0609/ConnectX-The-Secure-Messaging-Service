import { apiRequest } from './apiClient';
import { ConnectionRequestDto, UserConnectionDto } from '../types';

export const connectionApi = {
  sendRequest: (recipientId: number) =>
    apiRequest<ConnectionRequestDto>('/connections/requests', {
      method: 'POST',
      body: JSON.stringify({ recipientId }),
    }),

  getPendingIncoming: () => apiRequest<ConnectionRequestDto[]>('/connections/requests/pending'),

  getSentOutgoing: () => apiRequest<ConnectionRequestDto[]>('/connections/requests/sent'),

  acceptRequest: (requestId: number) =>
    apiRequest<ConnectionRequestDto>(`/connections/requests/${requestId}/accept`, {
      method: 'POST',
    }),

  rejectRequest: (requestId: number) =>
    apiRequest<ConnectionRequestDto>(`/connections/requests/${requestId}/reject`, {
      method: 'POST',
    }),

  cancelRequest: (requestId: number) =>
    apiRequest<ConnectionRequestDto>(`/connections/requests/${requestId}/cancel`, {
      method: 'POST',
    }),

  getConnections: () => apiRequest<UserConnectionDto[]>('/connections'),

  removeConnection: (userId: number) =>
    apiRequest<string>(`/connections/${userId}`, {
      method: 'DELETE',
    }),
};
