import { config } from '../config/environment';
import { ApiResponse, ApiError, AuthResponse } from '../types';

export class ApiRequestError extends Error {
  status: number;
  code: string;
  path?: string;

  constructor(status: number, code: string, message: string, path?: string) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = code;
    this.path = path;
  }
}

let refreshPromise: Promise<string | null> | null = null;

async function attemptTokenRefresh(): Promise<string | null> {
  const refreshToken = localStorage.getItem('connectx_refresh_token');
  if (!refreshToken) {
    return null;
  }

  try {
    const response = await fetch(`${config.apiBaseUrl}/auth/refresh`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ refreshToken }),
    });

    if (response.status === 401 || response.status === 403) {
      // Refresh token is genuinely invalid / expired -> trigger logout
      handleAuthFailure();
      return null;
    }

    if (!response.ok) {
      return null;
    }

    const data = await response.json().catch(() => null);
    const authData: AuthResponse = data && data.success !== undefined ? data.data : data;

    if (authData && authData.accessToken) {
      localStorage.setItem('connectx_token', authData.accessToken);
      if (authData.refreshToken) {
        localStorage.setItem('connectx_refresh_token', authData.refreshToken);
      }
      if (authData.user) {
        localStorage.setItem('connectx_user', JSON.stringify(authData.user));
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(
          new CustomEvent('connectx_token_refreshed', { detail: { token: authData.accessToken } })
        );
      }
      return authData.accessToken;
    }
    return null;
  } catch {
    // Network failure / backend restarting: do NOT clear local user session
    return null;
  }
}

export function handleAuthFailure(): void {
  localStorage.removeItem('connectx_token');
  localStorage.removeItem('connectx_refresh_token');
  localStorage.removeItem('connectx_user');
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event('connectx_auth_expired'));
  }
}

export async function apiRequest<T>(
  endpoint: string,
  options?: RequestInit,
  isRetry = false
): Promise<T> {
  const token = localStorage.getItem('connectx_token');

  const headers: Record<string, string> = {
    ...(options?.headers as Record<string, string>),
  };

  const isFormData = typeof FormData !== 'undefined' && options?.body instanceof FormData;
  if (!isFormData) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
  }

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const url = `${config.apiBaseUrl}${endpoint}`;

  try {
    const response = await fetch(url, {
      ...options,
      headers,
    });

    // Handle 401 Unauthorized for authenticated endpoints
    if (
      response.status === 401 &&
      !isRetry &&
      !endpoint.startsWith('/auth/login') &&
      !endpoint.startsWith('/auth/register') &&
      !endpoint.startsWith('/auth/refresh')
    ) {
      if (!refreshPromise) {
        refreshPromise = attemptTokenRefresh().finally(() => {
          refreshPromise = null;
        });
      }

      const newToken = await refreshPromise;
      if (newToken) {
        return apiRequest<T>(endpoint, options, true);
      } else {
        handleAuthFailure();
      }
    }

    const responseData = await response.json().catch(() => null);

    if (!response.ok) {
      if (responseData && responseData.code && responseData.message) {
        const error: ApiError = responseData;
        throw new ApiRequestError(
          error.status || response.status,
          error.code,
          error.message,
          error.path
        );
      }
      throw new ApiRequestError(
        response.status,
        'HTTP_ERROR',
        `Request failed with status ${response.status}`
      );
    }

    const apiResponse = responseData as ApiResponse<T>;
    if (apiResponse && apiResponse.success !== undefined) {
      return apiResponse.data;
    }

    return responseData as T;
  } catch (error) {
    if (error instanceof ApiRequestError) {
      throw error;
    }
    throw new ApiRequestError(
      0,
      'NETWORK_ERROR',
      `Unable to connect to backend server at ${config.apiBaseUrl}. Please check your network connection.`
    );
  }
}

export async function uploadRequest<T>(endpoint: string, formData: FormData): Promise<T> {
  return apiRequest<T>(endpoint, {
    method: 'POST',
    body: formData,
  });
}

