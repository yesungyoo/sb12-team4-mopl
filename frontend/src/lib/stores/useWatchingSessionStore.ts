import { create } from 'zustand';
import { getWatchingSessionsByContent } from '@/lib/api/watching-sessions';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { WatchingSessionDto, FindWatchingSessionsByContentParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

type WatchingSessionParams = FindWatchingSessionsByContentParams & { contentId: string };

const useWatchingSessionStore = create<PaginatedStore<WatchingSessionDto, WatchingSessionParams>>((set, get) =>
  createPaginatedStoreActions<WatchingSessionDto, WatchingSessionParams>({
    set,
    get,
    fetchApi: (params) => {
      const { contentId, ...queryParams } = params;
      return getWatchingSessionsByContent(contentId, queryParams);
    },
    initialData: {
      params: { contentId: '', limit: 50, sortBy: 'createdAt', sortDirection: 'ASCENDING' },
    },
  })
);

export default useWatchingSessionStore;
