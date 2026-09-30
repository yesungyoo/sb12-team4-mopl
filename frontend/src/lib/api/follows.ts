/**
 * Follows API Module
 *
 * Handles follow relationship operations:
 * - Create and cancel follow
 * - Check follow status
 * - Get follower count
 */

import { isAxiosError } from 'axios';
import apiClient from './client';
import type { FollowDto, FollowRequest } from '@/lib/types';

/**
 * Follow user (팔로우)
 * POST /api/follows
 *
 * @param data - Follow request data (followeeId)
 * @returns Created follow information
 */
export const createFollow = async (data: FollowRequest): Promise<FollowDto> => {
  const response = await apiClient.post<FollowDto>('/api/follows', data);
  return response.data;
};

/**
 * Unfollow user (팔로우 취소)
 * DELETE /api/follows/{followId}
 *
 * @param followId - Follow ID to cancel
 *
 * Note: Users can only cancel their own follows
 */
export const cancelFollow = async (followId: string): Promise<void> => {
  await apiClient.delete(`/api/follows/${followId}`);
};

/**
 * Check if user is followed by me (특정 유저를 내가 팔로우하는지 여부 조회)
 * GET /api/follows/followed-by-me
 *
 * @param followeeId - User ID to check
 * @returns FollowDto if following, null if not
 */
export const isFollowedByMe = async (followeeId: string): Promise<FollowDto | null> => {
  try {
    const response = await apiClient.get<FollowDto>('/api/follows/followed-by-me', {
      params: { followeeId },
    });
    return response.data;
  } catch (err) {
    if (isAxiosError(err) && err.response?.status === 404) {
      return null;
    }
    throw err;
  }
};

/**
 * Get follower count (특정 유저의 팔로워 수 조회)
 * GET /api/follows/count
 *
 * @param followeeId - User ID to get follower count
 * @returns Follower count
 */
export const getFollowerCount = async (followeeId: string): Promise<number> => {
  const response = await apiClient.get<number>('/api/follows/count', {
    params: { followeeId },
  });
  return response.data;
};
