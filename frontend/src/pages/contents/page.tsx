import {
  useCallback,
  useEffect,
  useState,
} from 'react';
import { useInView } from 'react-intersection-observer';

import useContentStore from '@/lib/stores/useContentStore';
import type { ContentType } from '@/lib/types';

import ContentGrid from './components/ContentGrid';
import FilterTabs, {
  type ContentsTab,
} from './components/FilterTabs';
import HomeTab from './components/HomeTab';
import MovieTab from './components/MovieTab';
import NewTab from './components/NewTab';
import SearchBar from './components/SearchBar';
import SortDropdown, {
  type SortOption,
} from './components/SortDropdown';
import SportTab from './components/SportTab';
import TvSeriesTab from './components/TvSeriesTab';

const TAB_LIST_TITLE: Record<ContentsTab, string> = {
  home: '전체 콘텐츠',
  new: '새로 추가된 콘텐츠 전체',
  movie: '영화 - 전체',
  tvSeries: 'TV 시리즈 - 전체',
  sport: '스포츠 - 전체',
};

const TAB_LIST_DESCRIPTION: Record<ContentsTab, string> = {
  home: 'MOPL의 모든 콘텐츠를 둘러보세요.',
  new: '최근 MOPL에 추가된 콘텐츠부터 확인해보세요.',
  movie: 'MOPL에 등록된 영화를 둘러보세요.',
  tvSeries: 'MOPL에 등록된 TV 시리즈를 둘러보세요.',
  sport: 'MOPL에 등록된 스포츠 콘텐츠를 둘러보세요.',
};

const TAB_CONTENT_TYPE: Partial<
  Record<ContentsTab, ContentType>
> = {
  movie: 'movie',
  tvSeries: 'tvSeries',
  sport: 'sport',
};

export default function ContentsPage() {
  const {
    data,
    loading,
    fetch,
    fetchMore,
    hasNext,
    updateParams,
  } = useContentStore();

  const [selectedTab, setSelectedTab] =
    useState<ContentsTab>('home');

  const [sortValue, setSortValue] =
    useState('popular');

  const [searchQuery, setSearchQuery] =
    useState('');

  const normalizedSearchQuery =
    searchQuery.trim();

  const isSearchMode =
    normalizedSearchQuery.length > 0;

  const {
    ref: sentinelRef,
    inView,
  } = useInView({
    threshold: 0,
    rootMargin: '100px',
  });

  // [추가] 페이지 최초 진입 시 전체 콘텐츠 조회
  useEffect(() => {
    void fetch();
  }, [fetch]);

  // 실제 Content API cursor pagination
  useEffect(() => {
    if (
      inView
      && hasNext()
      && !loading
    ) {
      void fetchMore();
    }
  }, [
    fetchMore,
    hasNext,
    inView,
    loading,
  ]);

  const applyTabParams = useCallback(
    (tab: ContentsTab) => {
      if (tab === 'home') {
        setSortValue('popular');

        updateParams({
          keywordLike: undefined,
          typeEqual: undefined,
          sortBy: 'watcherCount',
          sortDirection: 'DESCENDING',
        });

        return;
      }

      if (tab === 'new') {
        setSortValue('latest');

        updateParams({
          keywordLike: undefined,
          typeEqual: undefined,
          sortBy: 'createdAt',
          sortDirection: 'DESCENDING',
        });

        return;
      }

      setSortValue('popular');

      updateParams({
        keywordLike: undefined,
        typeEqual: TAB_CONTENT_TYPE[tab],
        sortBy: 'watcherCount',
        sortDirection: 'DESCENDING',
      });
    },
    [updateParams],
  );

  const handleTabChange = useCallback(
    (tab: ContentsTab) => {
      setSelectedTab(tab);

      if (!normalizedSearchQuery) {
        applyTabParams(tab);
      }
    },
    [
      applyTabParams,
      normalizedSearchQuery,
    ],
  );

  const handleSearch = useCallback(
    (query: string) => {
      const normalizedQuery = query.trim();

      setSearchQuery(query);

      if (normalizedQuery) {
        updateParams({
          keywordLike: normalizedQuery,
          typeEqual: undefined,
        });

        return;
      }

      applyTabParams(selectedTab);
    },
    [
      applyTabParams,
      selectedTab,
      updateParams,
    ],
  );

  const handleSortChange = useCallback(
    (option: SortOption) => {
      const nextSortValue =
        option.sortBy === 'createdAt'
          ? 'latest'
          : option.sortBy === 'watcherCount'
            ? 'popular'
            : 'rating';

      setSortValue(nextSortValue);

      updateParams({
        sortBy: option.sortBy,
        sortDirection: option.sortDirection,
      });
    },
    [updateParams],
  );

  const listTitle = isSearchMode
    ? `'${normalizedSearchQuery}' 검색 결과`
    : TAB_LIST_TITLE[selectedTab];

  const listDescription = isSearchMode
    ? 'MOPL 전체 콘텐츠에서 검색한 결과예요.'
    : TAB_LIST_DESCRIPTION[selectedTab];

  return (
    <div
      className="
        flex
        min-w-0
        flex-col
        gap-9
        px-[70px]
        py-10
      "
    >
      <header className="flex min-w-0 flex-col gap-6">
        <div
          className="
            flex
            flex-wrap
            items-center
            justify-between
            gap-5
          "
        >
          <h1 className="text-header1-b text-white">
            콘텐츠 같이 보기
          </h1>

          <SearchBar
            onSearch={handleSearch}
            placeholder="MOPL 전체 콘텐츠 통합 검색"
          />
        </div>

        <FilterTabs
          selectedTab={selectedTab}
          onTabChange={handleTabChange}
        />

        <div className="h-px w-full bg-gray-900" />
      </header>

      {!isSearchMode && selectedTab === 'home' && (
        <HomeTab />
      )}

      {!isSearchMode && selectedTab === 'new' && (
        <NewTab />
      )}

      {!isSearchMode && selectedTab === 'movie' && (
        <MovieTab />
      )}

      {!isSearchMode && selectedTab === 'tvSeries' && (
        <TvSeriesTab />
      )}

      {!isSearchMode && selectedTab === 'sport' && (
        <SportTab />
      )}

      <section
        className="
          flex
          min-w-0
          flex-col
          gap-7
          border-t
          border-gray-900
          pt-9
        "
      >
        <div
          className="
            flex
            flex-wrap
            items-end
            justify-between
            gap-4
          "
        >
          <div>
            <h2 className="text-title1-b text-white">
              {listTitle}
            </h2>

            <p className="mt-2 text-body3-m text-gray-500">
              {listDescription}
            </p>
          </div>

          <SortDropdown
            value={sortValue}
            onValueChange={handleSortChange}
          />
        </div>

        <ContentGrid
          contents={data}
          loading={loading}
        />

        {!loading && hasNext() && (
          <div
            ref={sentinelRef}
            className="flex h-10 items-center justify-center"
          />
        )}

        {loading && (
          <div className="flex justify-center py-4">
            <div
              className="
                h-8
                w-8
                animate-spin
                rounded-full
                border-4
                border-gray-700
                border-t-pink-500
              "
            />
          </div>
        )}
      </section>
    </div>
  );
}