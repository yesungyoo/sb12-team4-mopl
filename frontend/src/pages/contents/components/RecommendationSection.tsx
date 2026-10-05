import {
  ChevronLeft,
  ChevronRight,
  Sparkles,
} from 'lucide-react';
import {
  useEffect,
  useRef,
} from 'react';

import useRecommendationStore from '@/lib/stores/useRecommendationStore';
import useHorizontalScrollFade from '@/pages/contents/hooks/useHorizontalScrollFade';

import RecommendationCard from './RecommendationCard';

interface RecommendationSectionProps {
  title?: string;
  description?: string;
}

export default function RecommendationSection({
  title,
  description,
}: RecommendationSectionProps) {
  const scrollContainerRef =
    useRef<HTMLDivElement>(null);

  const {
    data,
    loading,
    error,
    fetch,
  } = useRecommendationStore();

  useEffect(() => {
    void fetch();
  }, [fetch]);

  const recommendations = data;

  const sectionTitle =
    title ?? '사용자님을 위한 AI 추천';

  const sectionDescription =
    description
    ?? '시청 기록과 리뷰 취향을 바탕으로 골라봤어요.';

  const {
    showLeftFade,
    showRightFade,
  } = useHorizontalScrollFade(
    scrollContainerRef,
    `${loading}-${recommendations.length}`,
  );

  const handleScrollLeft = () => {
    scrollContainerRef.current?.scrollBy({
      left: -552,
      behavior: 'smooth',
    });
  };

  const handleScrollRight = () => {
    scrollContainerRef.current?.scrollBy({
      left: 552,
      behavior: 'smooth',
    });
  };

  if (error) {
    return (
      <section
        className="
          w-full
          min-w-0
          max-w-full
          overflow-hidden
          rounded-3xl
          border
          border-gray-800
          bg-gray-900/50
          px-6
          py-8
        "
      >
        <div className="flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-pink-400" />

          <h2 className="text-xl font-bold text-white">
            {sectionTitle}
          </h2>
        </div>

        <p className="mt-3 text-body2-m text-gray-500">
          추천 콘텐츠를 불러오지 못했어요.
        </p>
      </section>
    );
  }

  if (!loading && recommendations.length === 0) {
    return null;
  }

  return (
    <section
      className="
        relative
        w-full
        min-w-0
        max-w-full
        overflow-hidden
        rounded-3xl
        border
        border-gray-800
        bg-gray-900/50
        px-6
        py-7
      "
    >
      <div
        className="
          pointer-events-none
          absolute
          -left-20
          -top-28
          h-64
          w-64
          rounded-full
          bg-pink-500/5
          blur-3xl
        "
      />

      <div
        className="
          relative
          mb-6
          flex
          items-end
          justify-between
          gap-4
        "
      >
        <div className="flex items-center gap-3">
          <div
            className="
              flex
              h-9
              w-9
              items-center
              justify-center
              rounded-xl
              bg-pink-500/10
            "
          >
            <Sparkles className="h-5 w-5 text-pink-400" />
          </div>

          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold text-white">
                {sectionTitle}
              </h2>

              <span
                className="
                  rounded-full
                  bg-pink-500/10
                  px-2
                  py-1
                  text-[11px]
                  font-semibold
                  text-pink-300
                "
              >
                AI
              </span>
            </div>

            <p className="mt-1 text-body2-m text-gray-400">
              {sectionDescription}
            </p>
          </div>
        </div>

        {!loading && recommendations.length > 0 && (
          <div className="hidden items-center gap-2 sm:flex">
            <button
              type="button"
              onClick={handleScrollLeft}
              aria-label="이전 추천 콘텐츠 보기"
              className="
                flex
                h-9
                w-9
                items-center
                justify-center
                rounded-full
                border
                border-gray-700
                bg-gray-800
                text-gray-300
                transition
                hover:border-gray-600
                hover:bg-gray-700
                hover:text-white
              "
            >
              <ChevronLeft className="h-5 w-5" />
            </button>

            <button
              type="button"
              onClick={handleScrollRight}
              aria-label="다음 추천 콘텐츠 보기"
              className="
                flex
                h-9
                w-9
                items-center
                justify-center
                rounded-full
                border
                border-gray-700
                bg-gray-800
                text-gray-300
                transition
                hover:border-gray-600
                hover:bg-gray-700
                hover:text-white
              "
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </div>
        )}
      </div>

      {loading ? (
        <div className="flex w-full min-w-0 max-w-full gap-4 overflow-hidden">
          {Array.from({ length: 4 }).map((_, index) => (
            <RecommendationCardSkeleton key={index} />
          ))}
        </div>
      ) : (
        <div className="relative min-w-0 overflow-hidden">
          <div
            ref={scrollContainerRef}
            className="
              scrollbar-hide
              flex
              w-full
              min-w-0
              max-w-full
              gap-4
              overflow-x-auto
              overflow-y-hidden
              scroll-smooth
              pb-1
            "
          >
            {recommendations.map((recommendation) => (
              <RecommendationCard
                key={recommendation.contentId}
                recommendation={recommendation}
              />
            ))}
          </div>

          {showLeftFade && (
            <div
              className="
                pointer-events-none
                absolute
                inset-y-0
                left-0
                z-10
                w-24
                bg-gradient-to-r
                from-gray-950/95
                via-gray-950/55
                to-transparent
              "
            />
          )}

          {showRightFade && (
            <div
              className="
                pointer-events-none
                absolute
                inset-y-0
                right-0
                z-10
                w-24
                bg-gradient-to-l
                from-gray-950/95
                via-gray-950/55
                to-transparent
              "
            />
          )}
        </div>
      )}
    </section>
  );
}

function RecommendationCardSkeleton() {
  return (
    <div
      className="
        w-[260px]
        flex-shrink-0
        animate-pulse
        overflow-hidden
        rounded-2xl
        border
        border-gray-800
        bg-gray-900
      "
    >
      <div className="aspect-[260/390] w-full bg-gray-800" />

      <div className="p-5">
        <div className="flex justify-between">
          <div className="h-7 w-20 rounded-full bg-gray-800" />
          <div className="h-5 w-16 rounded bg-gray-800" />
        </div>

        <div className="mt-5 h-6 w-4/5 rounded bg-gray-800" />
        <div className="mt-4 h-[125px] rounded-xl bg-gray-800" />

        <div className="mt-4 flex gap-2">
          <div className="h-6 w-14 rounded-full bg-gray-800" />
          <div className="h-6 w-16 rounded-full bg-gray-800" />
          <div className="h-6 w-12 rounded-full bg-gray-800" />
        </div>
      </div>
    </div>
  );
}