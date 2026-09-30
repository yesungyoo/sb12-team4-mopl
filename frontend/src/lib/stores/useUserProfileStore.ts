import { create } from 'zustand';
import { getUserById } from '@/lib/api/users';
import { createBaseStoreActions } from '@/lib/stores/actions';
import type { UserDto } from '@/lib/types';
import type { BaseStore } from '@/lib/stores/types';

interface UserProfileParams {
  userId: string;
}

const useUserProfileStore = create<BaseStore<UserDto, UserProfileParams>>((set, get) =>
  createBaseStoreActions<UserDto, UserProfileParams>({
    set,
    get,
    fetchApi: (params) => getUserById(params.userId),
    initialData: {
      params: { userId: '' },
    },
  })
);

export default useUserProfileStore;
