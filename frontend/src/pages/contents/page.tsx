import { useEffect, useState, useCallback } from 'react';
import { useInView } from 'react-intersection-observer';
import useContentStore from '@/lib/stores/useContentStore';
import type { ContentType } from '@/lib/types';
import FilterTabs from './components/FilterTabs';
import SearchBar from './components/SearchBar';
import SortDropdown, { type SortOption } from './components/SortDropdown';
import ContentGrid from './components/ContentGrid';

export default function ContentsPage() {
  const { data, loading, fetchMore, hasNext, updateParams } = useContentStore();
  const [selectedType, setSelectedType] = useState<ContentType | 'ALL'>('ALL');
  const [sortValue, setSortValue] = useState('popular');

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

  // Handle filter change
  const handleTypeChange = useCallback(
    (type: ContentType | 'ALL') => {
      setSelectedType(type);
      if (type === 'ALL') {
        updateParams({ typeEqual: undefined });
      } else {
        updateParams({ typeEqual: type });
      }
    },
    [updateParams]
  );

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
      setSortValue(
        option.sortBy === 'createdAt'
          ? 'latest'
          : option.sortBy === 'watcherCount'
            ? 'popular'
            : 'rating'
      );
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
      <h1 className="text-header1-b text-white">콘텐츠 같이 보기</h1>

      {/* Filter & Search Bar */}
      <div className="flex items-center justify-between">
        <FilterTabs selectedType={selectedType} onTypeChange={handleTypeChange} />

        <div className="flex items-center gap-2.5">
          <SearchBar onSearch={handleSearch} />
          <SortDropdown value={sortValue} onValueChange={handleSortChange} />
        </div>
      </div>

      {/* Content Grid */}
      <ContentGrid contents={data} loading={loading} />

      {/* Infinite Scroll Sentinel */}
      {!loading && hasNext() && (
        <div ref={sentinelRef} className="h-10 flex items-center justify-center">

        </div>
      )}
      {loading && (
          <div className="w-8 h-8 border-4 border-gray-700 border-t-pink-500 rounded-full animate-spin" />
      )}
    </div>
  );
}
