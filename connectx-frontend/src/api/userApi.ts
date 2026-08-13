import { apiRequest, uploadRequest } from './apiClient';
import { User } from '../types';

export const userApi = {
  searchUsers: (username: string) =>
    apiRequest<User[]>(`/users/search?username=${encodeURIComponent(username)}`),

  getCurrentUser: () =>
    apiRequest<User>('/users/me'),

  getUserById: (userId: number) =>
    apiRequest<User>(`/users/${userId}`),

  updateProfile: (data: { displayName?: string; profileImageUrl?: string; status?: string }) =>
    apiRequest<User>('/users/me', {
      method: 'PATCH',
      body: JSON.stringify(data),
    }),

  uploadProfilePhoto: (file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return uploadRequest<User>('/users/me/profile-photo', formData);
  },

  removeProfilePhoto: () =>
    apiRequest<User>('/users/me/profile-photo', {
      method: 'DELETE',
    }),

  getIdentityKey: () =>
    apiRequest<{ masterPublicKey: string | null; masterPrivateKey: string | null }>('/users/me/identity-key'),

  saveIdentityKey: (data: { masterPublicKey: string; masterPrivateKey: string }) =>
    apiRequest<{ masterPublicKey: string; masterPrivateKey: string }>('/users/me/identity-key', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
};

