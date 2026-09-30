/**
 * Watching Sessions API Module
 *
 * Handles watching session operations:
 * - Get watching session by watcher
 * - Get watching sessions by content
 */

import apiClient from './client';
import type {
  WatchingSessionDto,
  CursorResponseWatchingSessionDto,
  FindWatchingSessionsByContentParams,
} from '@/lib/types';

/**
 * Get watching session by watcher (특정 사용자의 시청 세션 조회)
 * GET /api/users/{watcherId}/watching-sessions
 *
 * @param watcherId - Watcher user ID
 * @returns Watching session information (nullable)
 */
export const getWatchingSessionByWatcher = async (
  watcherId: string,
): Promise<WatchingSessionDto | null> => {
  const response = await apiClient.get<WatchingSessionDto>(
    `/api/users/${watcherId}/watching-sessions`,
  );
  return response.data;
};

/**
 * Get watching sessions by content (특정 콘텐츠의 시청 세션 목록 조회)
 * GET /api/contents/{contentId}/watching-sessions
 *
 * @param contentId - Content ID
 * @param params - Query parameters for filtering, sorting, and pagination
 * @returns Paginated list of watching sessions
 */
export const getWatchingSessionsByContent = async (
  contentId: string,
  params?: FindWatchingSessionsByContentParams,
): Promise<CursorResponseWatchingSessionDto> => {
  const response = await apiClient.get<CursorResponseWatchingSessionDto>(
    `/api/contents/${contentId}/watching-sessions`,
    { params },
  );
  return response.data;
};
