import {create} from 'zustand';
import {createBaseStoreActions} from '@/lib/stores/actions';
import type {ConversationDto} from '@/lib/types';
import type {BaseStore} from '@/lib/stores/types';
import {getConversationById} from "@/lib/api";

interface ConversationDetailParams {
  conversationId: string;
}

const useContentDetailStore = create<BaseStore<ConversationDto, ConversationDetailParams>>((set, get) =>
  createBaseStoreActions<ConversationDto, ConversationDetailParams>({
    set,
    get,
    fetchApi: (params) => getConversationById(params.conversationId),
    initialData: {
      params: { conversationId: '' },
    },
  })
);

export default useContentDetailStore;
