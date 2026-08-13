import { apiRequest } from './apiClient';
import { AuthResponse, User } from '../types';

export const authApi = {
  register: (data: { username: string; email: string; password: string; displayName?: string }) =>
    apiRequest<AuthResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  login: (data: { usernameOrEmail: string; password: string }) =>
    apiRequest<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  logout: () =>
    apiRequest<string>('/auth/logout', {
      method: 'POST',
    }),

  refresh: (refreshToken: string) =>
    apiRequest<AuthResponse>('/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }),
};
