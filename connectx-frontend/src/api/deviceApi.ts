import { apiRequest } from './apiClient';
import { Device, UserPublicKey } from '../types';

export const deviceApi = {
  registerDevice: (data: { deviceName: string; publicKey: string; keyAlgorithm: string }) =>
    apiRequest<Device>('/devices', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  getMyDevices: () =>
    apiRequest<Device[]>('/devices'),

  deactivateDevice: (deviceId: number) =>
    apiRequest<string>(`/devices/${deviceId}`, {
      method: 'DELETE',
    }),

  markDeviceSeen: (deviceId: number) =>
    apiRequest<Device>(`/devices/${deviceId}/seen`, {
      method: 'POST',
    }),

  getUserPublicKeys: (userId: number) =>
    apiRequest<UserPublicKey[]>(`/users/${userId}/devices/public-keys`),
};
