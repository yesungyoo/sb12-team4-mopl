import type {ContentDto} from '@/lib/types';
import ContentCard from './ContentCard';

interface ContentGridProps {
  contents: ContentDto[];
  loading?: boolean;
}

export default function ContentGrid({ contents, loading }: ContentGridProps) {
  if (loading && contents.length === 0) {
    return (
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-x-4 gap-y-6 sm:gap-x-5 sm:gap-y-8 md:gap-x-[30px] md:gap-y-[40px]">
        {Array.from({ length: 30 }).map((_, index) => (
          <ContentCardSkeleton key={index} />
        ))}
      </div>
    );
  }

  if (contents.length === 0) {
    return (
      <div className="flex items-center justify-center h-[400px]">
        <p className="text-body2-m text-gray-400">콘텐츠가 없습니다.</p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-x-4 gap-y-6 sm:gap-x-5 sm:gap-y-8 md:gap-x-[30px] md:gap-y-[40px]">
      {contents.map((content) => (
        <ContentCard key={content.id} content={content} />
      ))}
    </div>
  );
}

function ContentCardSkeleton() {
  return (
    <div className="flex flex-col gap-3 animate-pulse">
      {/* Thumbnail Skeleton */}
      <div className="w-full aspect-[260/390] rounded-2xl bg-gray-800" />

      {/* Info Skeleton */}
      <div className="flex flex-col gap-1.5 pt-1.5">
        <div className="h-5 bg-gray-800 rounded w-3/4" />
        <div className="h-4 bg-gray-800 rounded w-full" />
        <div className="h-4 bg-gray-800 rounded w-5/6" />
      </div>

      {/* Rating Skeleton */}
      <div className="flex items-center gap-0.5 pb-1">
        <div className="w-4 h-4 bg-gray-800 rounded" />
        <div className="h-3 bg-gray-800 rounded w-16" />
      </div>

      {/* Tags Skeleton */}
      <div className="flex gap-1.5">
        <div className="h-[26px] bg-gray-800 rounded-full w-16" />
        <div className="h-[26px] bg-gray-800 rounded-full w-12" />
      </div>
    </div>
  );
}
