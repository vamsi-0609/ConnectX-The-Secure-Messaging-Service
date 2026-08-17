import { apiRequest } from './apiClient';
import { UserBlockDto } from '../types';

export const blockApi = {
  blockUser: (userId: number) =>
    apiRequest<UserBlockDto>(`/blocks/${userId}`, {
      method: 'POST',
    }),

  unblockUser: (userId: number) =>
    apiRequest<string>(`/blocks/${userId}`, {
      method: 'DELETE',
    }),

  getBlocks: () => apiRequest<UserBlockDto[]>('/blocks'),
};
