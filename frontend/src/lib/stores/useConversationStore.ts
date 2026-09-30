import { create } from 'zustand';
import { getConversations } from '@/lib/api/conversations';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { ConversationDto, FindConversationsParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const useConversationStore = create<PaginatedStore<ConversationDto, FindConversationsParams>>((set, get) =>
  createPaginatedStoreActions<ConversationDto, FindConversationsParams>({
    set,
    get,
    fetchApi: getConversations,
    initialData: {
      params: { limit: 20, sortBy: 'createdAt', sortDirection: 'DESCENDING' },
    },
  })
);

export default useConversationStore;
