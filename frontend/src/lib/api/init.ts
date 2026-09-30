/**
 * API Client Initialization
 *
 * Connects the API client with the auth store to enable:
 * - Automatic JWT token injection
 * - CSRF token handling
 * - Automatic token refresh on 401 errors
 */

import { setTokenGetters } from './client';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import { getCsrfTokenFromCookie, refreshToken } from './auth';

/**
 * Initialize API client with auth store integration
 *
 * MUST be called before any API requests are made (typically in main.tsx)
 */
export const initializeApiClient = () => {
  setTokenGetters(
    // Get access token from auth store
    () => useAuthStore.getState().getAccessToken(),

    // Get CSRF token from cookie
    () => getCsrfTokenFromCookie(),

    // Handle token refresh
    async () => {
      const newTokenData = await refreshToken();
      useAuthStore.getState().update(newTokenData);
      return newTokenData.accessToken;
    },
  );
};
