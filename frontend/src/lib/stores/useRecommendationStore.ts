import { create } from 'zustand';

import { getRecommendations } from '@/lib/api/recommendations';
import { createListStoreActions } from '@/lib/stores/actions';
import type { ListStore } from '@/lib/stores/types';
import type { RecommendationItem } from '@/lib/types';

type RecommendationParams = Record<string, never>;

const useRecommendationStore = create<
  ListStore<RecommendationItem, RecommendationParams>
>((set, get) =>
  createListStoreActions<RecommendationItem, RecommendationParams>({
    set,
    get,
    fetchApi: () => getRecommendations(),
    keyExtractor: (item) => item.contentId,
  }),
);

export default useRecommendationStore;