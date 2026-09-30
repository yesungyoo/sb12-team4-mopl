/**
 * Notifications API Module
 *
 * Handles notification operations:
 * - Notification listing
 * - Mark as read
 */

import apiClient from './client';
import type { CursorResponseNotificationDto, GetNotificationsParams } from '@/lib/types';

/**
 * Get notifications list with cursor pagination (알림 목록 조회)
 * GET /api/notifications
 *
 * @param params - Query parameters for sorting and pagination
 * @returns Paginated list of notifications
 *
 * Note: Only returns requester's notifications
 */
export const getNotifications = async (
  params?: GetNotificationsParams,
): Promise<CursorResponseNotificationDto> => {
  const response = await apiClient.get<CursorResponseNotificationDto>('/api/notifications', {
    params,
  });
  return response.data;
};

/**
 * Mark notification as read (알림 읽음 처리)
 * DELETE /api/notifications/{notificationId}
 *
 * @param notificationId - Notification ID to mark as read
 */
export const markNotificationAsRead = async (notificationId: string): Promise<void> => {
  await apiClient.delete(`/api/notifications/${notificationId}`);
};
