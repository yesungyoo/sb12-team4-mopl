import type { ContentDto } from '@/lib/types';

import ContentCard from './ContentCard';

interface ContentGridProps {
  contents: ContentDto[];
  loading?: boolean;
}

const GRID_CLASS_NAME = `
  grid
  grid-cols-2
  gap-x-4
  gap-y-8
  sm:grid-cols-3
  sm:gap-x-5
  md:grid-cols-4
  md:gap-x-[30px]
  lg:grid-cols-5
  xl:grid-cols-6
`;

export default function ContentGrid({
  contents,
  loading = false,
}: ContentGridProps) {
  if (loading && contents.length === 0) {
    return (
      <div className={GRID_CLASS_NAME}>
        {Array.from({ length: 18 }).map(
          (_, index) => (
            <ContentCardSkeleton key={index} />
          ),
        )}
      </div>
    );
  }

  if (contents.length === 0) {
    return (
      <div
        className="
          flex
          h-[400px]
          items-center
          justify-center
        "
      >
        <p className="text-body2-m text-gray-400">
          콘텐츠가 없습니다.
        </p>
      </div>
    );
  }

  return (
    <div className={GRID_CLASS_NAME}>
      {contents.map((content) => (
        <ContentCard
          key={content.id}
          content={content}
        />
      ))}
    </div>
  );
}

function ContentCardSkeleton() {
  return (
    <div
      className="
        flex
        w-full
        animate-pulse
        flex-col
      "
    >
      <div
        className="
          aspect-[260/390]
          w-full
          rounded-2xl
          bg-gray-800
        "
      />

      <div
        className="
          mt-3
          h-5
          w-4/5
          rounded
          bg-gray-800
        "
      />

      <div
        className="
          mt-2
          h-4
          w-14
          rounded
          bg-gray-800
        "
      />

      <div className="mt-3 flex gap-1.5">
        <div
          className="
            h-[26px]
            w-[45%]
            rounded-full
            bg-gray-800
          "
        />

        <div
          className="
            h-[26px]
            w-[40%]
            rounded-full
            bg-gray-800
          "
        />
      </div>
    </div>
  );
}