import type { PlaylistDto } from '@/lib/types';
import PlaylistCard from './PlaylistCard';

interface PlaylistGridProps {
  playlists: PlaylistDto[];
  loading?: boolean;
  error?: string;
}

function PlaylistCardSkeleton() {
  return (
    <div className="w-full h-[250px] rounded-[24px] bg-gray-800 animate-pulse border border-gray-700">
      <div className="p-8 flex items-start justify-between h-full">
        <div className="flex-1 flex flex-col justify-between h-full pr-6">
          {/* Title skeleton */}
          <div>
            <div className="h-7 bg-gray-700 rounded-md w-48 mb-5" />
            <div className="h-5 bg-gray-700 rounded-md w-64 mb-2" />
            <div className="h-5 bg-gray-700 rounded-md w-32" />
          </div>
          {/* Metadata skeleton */}
          <div className="flex items-center gap-2">
            <div className="h-4 bg-gray-700 rounded-md w-20" />
            <div className="h-4 bg-gray-700 rounded-md w-16" />
          </div>
        </div>
        {/* Thumbnail skeleton */}
        <div className="w-[130px] h-[190px] bg-gray-700 rounded-[16px]" />
      </div>
    </div>
  );
}

function EmptyState() {
  return (
    <div className="col-span-full flex flex-col items-center justify-center py-20">
      <p className="text-body1-m text-gray-400 mb-2">플레이리스트가 없습니다</p>
      <p className="text-body3-m text-gray-500">검색 조건을 변경해보세요</p>
    </div>
  );
}

function ErrorState({ message }: { message: string }) {
  return (
    <div className="col-span-full flex flex-col items-center justify-center py-20">
      <p className="text-body1-m text-red-notification mb-2">오류가 발생했습니다</p>
      <p className="text-body3-m text-gray-500">{message}</p>
    </div>
  );
}

export default function PlaylistGrid({ playlists, loading, error }: PlaylistGridProps) {
  // Show error state
  if (error) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-x-[30px] gap-y-[40px]">
        <ErrorState message={error} />
      </div>
    );
  }

  // Show loading skeletons on initial load
  if (loading && playlists.length === 0) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-x-[30px] gap-y-[40px]">
        {Array.from({ length: 6 }).map((_, index) => (
          <PlaylistCardSkeleton key={index} />
        ))}
      </div>
    );
  }

  // Show empty state
  if (!loading && playlists.length === 0) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-x-[30px] gap-y-[40px]">
        <EmptyState />
      </div>
    );
  }

  // Show playlist grid
  return (
    <div className="grid sm:grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-x-[30px] gap-y-[40px]">
      {playlists.map((playlist) => (
        <PlaylistCard key={playlist.id} playlist={playlist} />
      ))}

      {/* Show loading skeletons when fetching more */}
      {loading && playlists.length > 0 && (
        <>
          {Array.from({ length: 30 }).map((_, index) => (
            <PlaylistCardSkeleton key={`skeleton-${index}`} />
          ))}
        </>
      )}
    </div>
  );
}
