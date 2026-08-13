import { config } from '../config/environment';
import { ApiResponse, ApiError } from '../types';

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

export async function apiRequest<T>(
  endpoint: string,
  options?: RequestInit
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
