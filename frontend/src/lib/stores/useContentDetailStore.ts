import { create } from 'zustand';
import { getContent } from '@/lib/api/contents';
import { createBaseStoreActions } from '@/lib/stores/actions';
import type { ContentDto } from '@/lib/types';
import type { BaseStore } from '@/lib/stores/types';

interface ContentDetailParams {
  contentId: string;
}

const useContentDetailStore = create<BaseStore<ContentDto, ContentDetailParams>>((set, get) =>
  createBaseStoreActions<ContentDto, ContentDetailParams>({
    set,
    get,
    fetchApi: (params) => getContent(params.contentId),
    initialData: {
      params: { contentId: '' },
    },
  })
);

export default useContentDetailStore;
