/**
 * Playlists API Module
 *
 * Handles playlist management operations:
 * - Playlist CRUD operations
 * - Subscription management
 * - Content management in playlists
 */

import apiClient from './client';
import type {
  PlaylistDto,
  PlaylistCreateRequest,
  PlaylistUpdateRequest,
  CursorResponsePlaylistDto,
  FindPlaylistsParams,
} from '@/lib/types';

/**
 * Get playlists list with cursor pagination (플레이리스트 목록 조회)
 * GET /api/playlists
 *
 * @param params - Query parameters for filtering, sorting, and pagination
 * @returns Paginated list of playlists
 */
export const getPlaylists = async (params?: FindPlaylistsParams): Promise<CursorResponsePlaylistDto> => {
  const response = await apiClient.get<CursorResponsePlaylistDto>('/api/playlists', { params });
  return response.data;
};

/**
 * Get single playlist (플레이리스트 단건 조회)
 * GET /api/playlists/{playlistId}
 *
 * @param playlistId - Playlist ID to retrieve
 * @returns Playlist information
 */
export const getPlaylist = async (playlistId: string): Promise<PlaylistDto> => {
  const response = await apiClient.get<PlaylistDto>(`/api/playlists/${playlistId}`);
  return response.data;
};

/**
 * Create playlist (플레이리스트 생성)
 * POST /api/playlists
 *
 * @param data - Playlist data
 * @returns Created playlist information
 *
 * Note: Created playlist is owned by the API requester
 */
export const createPlaylist = async (data: PlaylistCreateRequest): Promise<PlaylistDto> => {
  const response = await apiClient.post<PlaylistDto>('/api/playlists', data);
  return response.data;
};

/**
 * Update playlist (플레이리스트 수정)
 * PATCH /api/playlists/{playlistId}
 *
 * @param playlistId - Playlist ID to update
 * @param data - Playlist data to update
 * @returns Updated playlist information
 *
 * Note: Only playlist owner can update
 */
export const updatePlaylist = async (
  playlistId: string,
  data: PlaylistUpdateRequest,
): Promise<PlaylistDto> => {
  const response = await apiClient.patch<PlaylistDto>(`/api/playlists/${playlistId}`, data);
  return response.data;
};

/**
 * Delete playlist (플레이리스트 삭제)
 * DELETE /api/playlists/{playlistId}
 *
 * @param playlistId - Playlist ID to delete
 *
 * Note: Only playlist owner can delete
 */
export const deletePlaylist = async (playlistId: string): Promise<void> => {
  await apiClient.delete(`/api/playlists/${playlistId}`);
};

/**
 * Subscribe to playlist (플레이리스트 구독)
 * POST /api/playlists/{playlistId}/subscription
 *
 * @param playlistId - Playlist ID to subscribe
 */
export const subscribePlaylist = async (playlistId: string): Promise<void> => {
  await apiClient.post(`/api/playlists/${playlistId}/subscription`);
};

/**
 * Unsubscribe from playlist (플레이리스트 구독 취소)
 * DELETE /api/playlists/{playlistId}/subscription
 *
 * @param playlistId - Playlist ID to unsubscribe
 */
export const unsubscribePlaylist = async (playlistId: string): Promise<void> => {
  await apiClient.delete(`/api/playlists/${playlistId}/subscription`);
};

/**
 * Add content to playlist (플레이리스트에 콘텐츠 추가)
 * POST /api/playlists/{playlistId}/contents/{contentId}
 *
 * @param playlistId - Playlist ID
 * @param contentId - Content ID to add
 *
 * Note: Only playlist owner can add content
 */
export const addContentToPlaylist = async (playlistId: string, contentId: string): Promise<void> => {
  await apiClient.post(`/api/playlists/${playlistId}/contents/${contentId}`);
};

/**
 * Remove content from playlist (플레이리스트에서 콘텐츠 삭제)
 * DELETE /api/playlists/{playlistId}/contents/{contentId}
 *
 * @param playlistId - Playlist ID
 * @param contentId - Content ID to remove
 *
 * Note: Only playlist owner can remove content
 */
export const removeContentFromPlaylist = async (
  playlistId: string,
  contentId: string,
): Promise<void> => {
  await apiClient.delete(`/api/playlists/${playlistId}/contents/${contentId}`);
};
