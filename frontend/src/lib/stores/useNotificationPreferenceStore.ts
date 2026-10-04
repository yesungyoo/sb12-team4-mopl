import { create } from 'zustand';
import {
  getNotificationPreferences,
  updateNotificationPreference,
} from '@/lib/api/notification-preferences';
import { createListStoreActions } from '@/lib/stores/actions';
import type { ListStore } from '@/lib/stores/types';
import type { NotificationPreferenceDto, NotificationType } from '@/lib/types';

interface NotificationPreferenceStore
    extends ListStore<NotificationPreferenceDto, Record<string, never>> {
  togglePreference: (type: NotificationType, enabled: boolean) => Promise<void>;
}

const useNotificationPreferenceStore = create<NotificationPreferenceStore>((set, get) => ({
  ...createListStoreActions<NotificationPreferenceDto, Record<string, never>>({
    set,
    get,
    fetchApi: () => getNotificationPreferences(),
    keyExtractor: (item) => item.type,
  }),

  togglePreference: async (type, enabled) => {
    const { update } = get();

    // 낙관적 업데이트
    update(type, { enabled });

    try {
      await updateNotificationPreference(type, enabled);
    } catch (error) {
      console.error('Failed to update notification preference:', error);
      // 실패 시 롤백
      update(type, { enabled: !enabled });
      throw error;
    }
  },
}));

export default useNotificationPreferenceStore;