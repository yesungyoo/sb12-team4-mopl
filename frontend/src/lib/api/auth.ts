/**
 * Authentication API Module
 *
 * Handles user authentication operations:
 * - Sign in / Sign out
 * - Token refresh
 * - Password reset
 * - CSRF token management
 */

import apiClient from './client';
import type { SignInRequest, JwtDto, ResetPasswordRequest } from '@/lib/types';

/**
 * Sign in (로그인)
 * POST /api/auth/sign-in
 *
 * @param credentials - User credentials (username = email, password)
 * @returns JWT token and user information
 *
 * Note: Uses application/x-www-form-urlencoded format
 */
export const signIn = async (credentials: SignInRequest): Promise<JwtDto> => {
  const params = new URLSearchParams();
  if (credentials.username) params.append('username', credentials.username);
  if (credentials.password) params.append('password', credentials.password);

  const response = await apiClient.post<JwtDto>('/api/auth/sign-in', params, {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  });

  return response.data;
};

/**
 * Sign out (로그아웃)
 * POST /api/auth/sign-out
 *
 * Note: Handled by SecurityFilterChain
 */
export const signOut = async (): Promise<void> => {
  await apiClient.post('/api/auth/sign-out');
};

/**
 * Refresh access token (토큰 재발급)
 * POST /api/auth/refresh
 *
 * @returns New JWT token and user information
 *
 * Note: Uses REFRESH_TOKEN cookie automatically (withCredentials: true)
 */
export const refreshToken = async (): Promise<JwtDto> => {
  const response = await apiClient.post<JwtDto>('/api/auth/refresh');
  return response.data;
};

/**
 * Reset password (비밀번호 초기화)
 * POST /api/auth/reset-password
 *
 * @param request - Email to send temporary password
 *
 * Note: Sends temporary password to email
 */
export const resetPassword = async (request: ResetPasswordRequest): Promise<void> => {
  await apiClient.post('/api/auth/reset-password', request);
};

/**
 * Get CSRF token (CSRF 토큰 조회)
 * GET /api/auth/csrf-token
 *
 * Note: Token is stored in XSRF-TOKEN cookie automatically
 */
export const getCsrfToken = async (): Promise<void> => {
  await apiClient.get('/api/auth/csrf-token');
};

/**
 * Get CSRF token from cookie
 *
 * @returns CSRF token string or null
 */
export const getCsrfTokenFromCookie = (): string | null => {
  const match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
};
