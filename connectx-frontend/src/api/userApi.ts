import { apiRequest, uploadRequest } from './apiClient';
import { User } from '../types';

export const userApi = {
  searchUsers: (username: string) =>
    apiRequest<User[]>(`/users/search?username=${encodeURIComponent(username)}`),

  getCurrentUser: () =>
    apiRequest<User>('/users/me'),

  getUserById: (userId: number) =>
    apiRequest<User>(`/users/${userId}`),

  // profileImageUrl is deliberately not accepted here -- profile photos only ever change via
  // uploadProfilePhoto/removeProfilePhoto below, which the backend derives a safe internal path
  // for. Sending an arbitrary profileImageUrl would now be rejected by the backend anyway (the
  // field was removed from UserProfileUpdateDto as part of closing a JWT-exfiltration path).
  updateProfile: (data: { username?: string; displayName?: string; status?: string; profilePhotoVisibility?: string }) =>
    apiRequest<User>('/users/me', {
      method: 'PATCH',
      body: JSON.stringify(data),
    }),

  requestEmailChangeOtp: (newEmail: string) =>
    apiRequest<string>('/users/me/email/request-otp', {
      method: 'POST',
      body: JSON.stringify({ newEmail }),
    }),

  verifyEmailChangeOtp: (newEmail: string, otpCode: string) =>
    apiRequest<User>('/users/me/email/verify-otp', {
      method: 'POST',
      body: JSON.stringify({ newEmail, otpCode }),
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

