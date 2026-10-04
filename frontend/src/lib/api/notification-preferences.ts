/**
 * Notification Preferences API Module
 *
 * Handles per-category notification on/off settings:
 * - Get all preferences
 * - Update a single preference
 */

import apiClient from './client';
import type { NotificationPreferenceDto, NotificationType } from '@/lib/types';

/**
 * Get notification preferences (알림 유형별 수신 설정 조회)
 * GET /api/notification-preferences
 */
export const getNotificationPreferences = async (): Promise<NotificationPreferenceDto[]> => {
  const response = await apiClient.get<NotificationPreferenceDto[]>('/api/notification-preferences');
  return response.data;
};

/**
 * Update a notification preference (알림 유형별 수신 설정 변경)
 * PATCH /api/notification-preferences/{type}
 */
export const updateNotificationPreference = async (
    type: NotificationType,
    enabled: boolean,
): Promise<void> => {
  await apiClient.patch(`/api/notification-preferences/${type}`, { enabled });
};