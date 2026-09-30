import { create } from 'zustand';
import { getReviews } from '@/lib/api/reviews';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { ReviewDto, FindReviewsParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const useReviewStore = create<PaginatedStore<ReviewDto, FindReviewsParams>>((set, get) =>
  createPaginatedStoreActions<ReviewDto, FindReviewsParams>({
    set,
    get,
    fetchApi: getReviews,
    initialData: {
      params: {
        limit: 20,
        sortDirection: 'DESCENDING',
        sortBy: 'createdAt',
      },
    },
  })
);

export default useReviewStore;
