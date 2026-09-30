import { create } from 'zustand';
import { getNotifications } from '@/lib/api/notifications';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { NotificationDto, GetNotificationsParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const useNotificationStore = create<PaginatedStore<NotificationDto, GetNotificationsParams>>((set, get) =>
  createPaginatedStoreActions<NotificationDto, GetNotificationsParams>({
    set,
    get,
    fetchApi: getNotifications,
    initialData: {
      params: { limit: 20, sortBy: 'createdAt', sortDirection: 'DESCENDING' },
    },
  })
);

export default useNotificationStore;
