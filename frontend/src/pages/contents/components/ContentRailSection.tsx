import {
  Clapperboard,
  ImageOff,
  Sparkles,
  Star,
  Trophy,
  Tv,
} from 'lucide-react';
import {
  useRef,
  useState,
} from 'react';
import { Link } from 'react-router-dom';

import useHorizontalScrollFade from '@/pages/contents/hooks/useHorizontalScrollFade';

export type SectionContentType =
  | 'MOVIE'
  | 'TV_SERIES'
  | 'SPORT';

export interface SectionContentItem {
  contentId: string;
  title: string;
  thumbnailUrl: string | null;
  type: SectionContentType;
  rating: number | null;
  tags: string[];
  badge?: string;
}

interface ContentRailSectionProps {
  title: string;
  description: string;
  contentType: SectionContentType | null;
  tag: string | null;
  items: SectionContentItem[];
  personalized?: boolean;
}

const CONTENT_TYPE_LABEL: Record<
  SectionContentType,
  string
> = {
  MOVIE: '영화',
  TV_SERIES: 'TV 시리즈',
  SPORT: '스포츠',
};

export default function ContentRailSection({
  title,
  description,
  contentType,
  tag,
  items,
  personalized = false,
}: ContentRailSectionProps) {
  const scrollContainerRef =
    useRef<HTMLDivElement>(null);

  const {
    showLeftFade,
    showRightFade,
  } = useHorizontalScrollFade(
    scrollContainerRef,
    `${title}-${items.length}`,
  );

  return (
    <section className="min-w-0">
      <div className="mb-5 flex items-start gap-3">
        <div
          className={[
            'flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl',
            personalized
              ? 'bg-pink-500/10'
              : 'bg-gray-900',
          ].join(' ')}
        >
          <SectionIcon
            contentType={contentType}
            personalized={personalized}
          />
        </div>

        <div className="min-w-0">
          <div
            className="
              flex
              flex-wrap
              items-center
              gap-2
            "
          >
            <h2
              className="
                text-title1-b-140
                text-white
              "
            >
              {title}
            </h2>

            {personalized && (
              <span
                className="
                  rounded-full
                  bg-pink-500/10
                  px-2.5
                  py-1
                  text-caption1-sb
                  text-pink-400
                "
              >
                맞춤 추천
              </span>
            )}

            {tag && (
              <span
                className="
                  max-w-[160px]
                  truncate
                  rounded-full
                  bg-gray-900
                  px-2.5
                  py-1
                  text-caption1-sb
                  text-gray-400
                "
                title={tag}
              >
                #{tag}
              </span>
            )}
          </div>

          <p
            className="
              mt-1
              text-body3-m
              text-gray-500
            "
          >
            {description}
          </p>
        </div>
      </div>

      <div
        className="
          relative
          min-w-0
          overflow-hidden
        "
      >
        <div
          ref={scrollContainerRef}
          className="
            scrollbar-hide
            flex
            min-w-0
            gap-4
            overflow-x-auto
            overflow-y-hidden
            pb-2
          "
        >
          {items.map((item) => (
            <ContentRailCard
              key={item.contentId}
              item={item}
            />
          ))}
        </div>

        {showLeftFade && (
          <ScrollFade side="left" />
        )}

        {showRightFade && (
          <ScrollFade side="right" />
        )}
      </div>
    </section>
  );
}

function SectionIcon({
  contentType,
  personalized,
}: {
  contentType: SectionContentType | null;
  personalized: boolean;
}) {
  if (personalized) {
    return (
      <Sparkles
        className="
          h-5
          w-5
          text-pink-400
        "
      />
    );
  }

  switch (contentType) {
    case 'MOVIE':
      return (
        <Clapperboard
          className="
            h-5
            w-5
            text-violet-400
          "
        />
      );

    case 'TV_SERIES':
      return (
        <Tv
          className="
            h-5
            w-5
            text-emerald-400
          "
        />
      );

    case 'SPORT':
      return (
        <Trophy
          className="
            h-5
            w-5
            text-amber-400
          "
        />
      );

    default:
      return (
        <Sparkles
          className="
            h-5
            w-5
            text-pink-400
          "
        />
      );
  }
}

function ContentRailCard({
  item,
}: {
  item: SectionContentItem;
}) {
  const [imageError, setImageError] =
    useState(false);

  const hasThumbnail =
    Boolean(item.thumbnailUrl)
    && !imageError;

  return (
    <Link
      to={`/contents/${item.contentId}`}
      className="
        group
        w-[200px]
        flex-shrink-0
        text-left
      "
    >
      <div
        className="
          relative
          aspect-[260/390]
          w-full
          overflow-hidden
          rounded-2xl
          bg-gray-800
        "
      >
        {hasThumbnail ? (
          <img
            src={item.thumbnailUrl ?? undefined}
            alt={item.title}
            className="
              h-full
              w-full
              object-cover
              transition-transform
              duration-300
              group-hover:scale-[1.03]
            "
            onError={() =>
              setImageError(true)
            }
          />
        ) : (
          <div
            className="
              flex
              h-full
              w-full
              flex-col
              items-center
              justify-center
              gap-2
              bg-gray-800
              text-gray-500
            "
          >
            <ImageOff className="h-8 w-8" />

            <span className="text-caption1-m">
              썸네일 없음
            </span>
          </div>
        )}

        {hasThumbnail && (
          <div
            className="
              pointer-events-none
              absolute
              inset-0
              bg-gradient-to-t
              from-black/50
              via-transparent
              to-transparent
            "
          />
        )}

        {item.badge && (
          <span
            className="
              absolute
              left-3
              top-3
              rounded-full
              bg-pink-500
              px-2.5
              py-1
              text-caption1-sb
              text-white
            "
          >
            {item.badge}
          </span>
        )}

        <span
          className="
            absolute
            bottom-3
            left-3
            rounded-full
            bg-black/70
            px-2.5
            py-1
            text-caption1-sb
            text-gray-100
          "
        >
          {CONTENT_TYPE_LABEL[item.type]}
        </span>
      </div>

      <div
        className="
          mt-3
          flex
          min-h-[92px]
          flex-col
        "
      >
        <h3
          className="
            line-clamp-1
            text-body1-b
            text-gray-50
          "
          title={item.title}
        >
          {item.title}
        </h3>

        <div
          className="
            mt-1.5
            flex
            min-h-[20px]
            items-center
          "
        >
          {item.rating != null && (
            <>
              <Star
                className="
                  h-4
                  w-4
                  fill-yellow-400
                  text-yellow-400
                "
              />

              <span
                className="
                  ml-1.5
                  text-body3-m
                  text-gray-400
                "
              >
                {item.rating.toFixed(1)}
              </span>
            </>
          )}
        </div>

        <div
          className="
            mt-auto
            flex
            min-h-[26px]
            gap-1.5
            overflow-hidden
            pt-2
          "
        >
          {item.tags
            .slice(0, 2)
            .map((itemTag, index) => (
              <span
                key={`${itemTag}-${index}`}
                title={itemTag}
                className="
                  max-w-[105px]
                  flex-shrink-0
                  truncate
                  rounded-full
                  bg-gray-900
                  px-2
                  py-1
                  text-caption1-m
                  text-gray-400
                "
              >
                #{itemTag}
              </span>
            ))}
        </div>
      </div>
    </Link>
  );
}

function ScrollFade({
  side,
}: {
  side: 'left' | 'right';
}) {
  return (
    <div
      className={[
        'pointer-events-none absolute inset-y-0 z-10 w-24',
        side === 'left'
          ? 'left-0 bg-gradient-to-r from-gray-950/95 via-gray-950/55 to-transparent'
          : 'right-0 bg-gradient-to-l from-gray-950/95 via-gray-950/55 to-transparent',
      ].join(' ')}
    />
  );
}
