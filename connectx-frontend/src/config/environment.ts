/// <reference types="vite/client" />

/**
 * Centralized Application Environment Configuration
 * Single source of truth for backend REST & WebSocket endpoints.
 * Dynamic fallback handles local dev, LAN IP, and ngrok tunnels seamlessly.
 */

const getGoogleMapsApiKey = (): string => {
  return import.meta.env.VITE_GOOGLE_MAPS_API_KEY || '';
};

const getApiBaseUrl = (): string => {
  const url = import.meta.env.VITE_API_BASE_URL;
  if (url) return url.replace(/\/$/, '');
  return '/api/v1';
};

const getWsBaseUrl = (): string => {
  const url = import.meta.env.VITE_WS_BASE_URL;
  if (url) return url.replace(/\/$/, '');
  
  const protocol = typeof window !== 'undefined' && window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const host = typeof window !== 'undefined' ? window.location.host : 'localhost:5173';
  return `${protocol}//${host}/ws`;
};

export const config = {
  apiBaseUrl: getApiBaseUrl(),
  wsBaseUrl: getWsBaseUrl(),
  googleMapsApiKey: getGoogleMapsApiKey(),
  appName: 'ConnectX',
  appVersion: '1.0.0',
};
