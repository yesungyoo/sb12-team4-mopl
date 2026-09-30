import { create } from 'zustand';
import { getContents } from '@/lib/api/contents';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { ContentDto, FindContentsParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const useContentStore = create<PaginatedStore<ContentDto, FindContentsParams>>((set, get) =>
  createPaginatedStoreActions<ContentDto, FindContentsParams>({
    set,
    get,
    fetchApi: getContents,
    initialData: {
      params: { limit: 20, sortBy: 'watcherCount', sortDirection: 'DESCENDING' },
    },
  })
);

export default useContentStore;
