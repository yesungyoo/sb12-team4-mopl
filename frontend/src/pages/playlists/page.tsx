import { useEffect, useState, useCallback } from 'react';
import { useInView } from 'react-intersection-observer';
import usePlaylistStore from '@/lib/stores/usePlaylistStore';
import SearchBar from '@/pages/contents/components/SearchBar';
import PlaylistSortDropdown, { type SortOption } from './components/PlaylistSortDropdown';
import PlaylistGrid from './components/PlaylistGrid';

export default function PlaylistsPage() {
  const { data, loading, error, fetchMore, hasNext, updateParams } = usePlaylistStore();
  const [sortValue, setSortValue] = useState('latest');

  const { ref: sentinelRef, inView } = useInView({
    threshold: 0,
    rootMargin: '100px',
  });

  // Infinite scroll
  useEffect(() => {
    if (inView && hasNext() && !loading) {
      fetchMore();
    }
  }, [inView, hasNext, loading, fetchMore]);

  // Handle search
  const handleSearch = useCallback(
    (query: string) => {
      if (query.trim()) {
        updateParams({ keywordLike: query });
      } else {
        updateParams({ keywordLike: undefined });
      }
    },
    [updateParams]
  );

  // Handle sort change
  const handleSortChange = useCallback(
    (option: SortOption) => {
      setSortValue(option.sortBy === 'updatedAt' ? 'latest' : 'popular');
      updateParams({
        sortBy: option.sortBy,
        sortDirection: option.sortDirection,
      });
    },
    [updateParams]
  );

  return (
    <div className="flex flex-col gap-10 px-[70px] py-10">
      {/* Page Title */}
      <h1 className="text-header1-b text-white">플레이리스트</h1>

      {/* Search & Sort Bar */}
      <div className="flex items-center justify-end">
        <div className="flex items-center gap-2.5">
          <SearchBar onSearch={handleSearch} />
          <PlaylistSortDropdown value={sortValue} onValueChange={handleSortChange} />
        </div>
      </div>

      {/* Playlist Grid */}
      <PlaylistGrid playlists={data} loading={loading} error={error} />

      {/* Infinite Scroll Sentinel */}
      {!loading && hasNext() && (
        <div ref={sentinelRef} className="h-10 flex items-center justify-center">
          {loading && (
            <div className="w-8 h-8 border-4 border-gray-700 border-t-pink-500 rounded-full animate-spin" />
          )}
        </div>
      )}
    </div>
  );
}
