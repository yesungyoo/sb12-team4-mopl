import { create } from 'zustand';
import { getDirectMessages } from '@/lib/api/conversations';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { DirectMessageDto, FindDmsParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

type DirectMessageParams = FindDmsParams & { conversationId: string };

const useDirectMessageStore = create<PaginatedStore<DirectMessageDto, DirectMessageParams>>((set, get) =>
  createPaginatedStoreActions<DirectMessageDto, DirectMessageParams>({
    set,
    get,
    fetchApi: (params) => {
      const { conversationId, ...queryParams } = params;
      return getDirectMessages(conversationId, queryParams);
    },
    initialData: {
      params: { conversationId: '', limit: 20, sortBy: 'createdAt', sortDirection: 'DESCENDING' },
    },
  })
);

export default useDirectMessageStore;
