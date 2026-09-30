import { create } from 'zustand';
import { getUsers } from '@/lib/api/users';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { UserDto, FindUsersParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const useUserStore = create<PaginatedStore<UserDto, FindUsersParams>>((set, get) =>
  createPaginatedStoreActions<UserDto, FindUsersParams>({
    set,
    get,
    fetchApi: getUsers,
    initialData: {
      params: { limit: 20, sortBy: 'name', sortDirection: 'ASCENDING' },
    },
  })
);

export default useUserStore;
