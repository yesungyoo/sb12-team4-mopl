import { create } from 'zustand';
import { getPlaylists } from '@/lib/api/playlists';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { PlaylistDto, FindPlaylistsParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const usePlaylistSubscriptionStore = create<PaginatedStore<PlaylistDto, FindPlaylistsParams>>((set, get) =>
  createPaginatedStoreActions<PlaylistDto, FindPlaylistsParams>({
    set,
    get,
    fetchApi: getPlaylists,
    initialData: {
      params: { limit: 20, sortBy: 'updatedAt', sortDirection: 'DESCENDING' },
    },
  })
);

export default usePlaylistSubscriptionStore;
