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

  requestForgotPasswordOtp: (email: string) =>
    apiRequest<string>('/auth/forgot-password/request-otp', {
      method: 'POST',
      body: JSON.stringify({ email }),
    }),

  verifyForgotPasswordOtp: (email: string, otpCode: string) =>
    apiRequest<string>('/auth/forgot-password/verify-otp', {
      method: 'POST',
      body: JSON.stringify({ email, otpCode }),
    }),

  resetPassword: (data: { email: string; otpCode: string; newPassword: string }) =>
    apiRequest<string>('/auth/forgot-password/reset-password', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
};
