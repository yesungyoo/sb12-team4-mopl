import {
  Clapperboard,
  ImageOff,
  Sparkles,
  Star,
  Trophy,
  Tv,
} from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import type {
  ContentTagDto,
  RecommendationItem,
} from '@/lib/types';

interface RecommendationCardProps {
  recommendation: RecommendationItem;
}

const CONTENT_TYPE_LABEL: Record<
  RecommendationItem['type'],
  string
> = {
  MOVIE: '영화',
  TV_SERIES: 'TV 시리즈',
  SPORT: '스포츠',
};

function ContentTypeIcon({
  type,
}: {
  type: RecommendationItem['type'];
}) {
  const className = 'h-4 w-4';

  switch (type) {
    case 'MOVIE':
      return (
        <Clapperboard className={className} />
      );

    case 'TV_SERIES':
      return (
        <Tv className={className} />
      );

    case 'SPORT':
      return (
        <Trophy className={className} />
      );

    default:
      return null;
  }
}

function getTagLabel(
  tag: ContentTagDto | string,
) {
  if (typeof tag === 'string') {
    return tag.trim();
  }

  return (
    tag.value?.trim()
    || tag.tag?.trim()
    || ''
  );
}

function formatVoteCount(
  value: number | null,
) {
  if (value == null) {
    return null;
  }

  if (value >= 1_000_000) {
    return `${(
      value / 1_000_000
    ).toFixed(1)}M`;
  }

  if (value >= 1_000) {
    return `${(
      value / 1_000
    ).toFixed(1)}K`;
  }

  return value.toLocaleString();
}

export default function RecommendationCard({
  recommendation,
}: RecommendationCardProps) {
  const [imageLoaded, setImageLoaded] =
    useState(false);

  const [imageError, setImageError] =
    useState(false);

  const hasThumbnail =
    Boolean(recommendation.thumbnailUrl)
    && !imageError;

  const voteCount = formatVoteCount(
    recommendation.externalVoteCount,
  );

  const displayTags = (
    recommendation.tags ?? []
  )
    .map((tag) =>
      getTagLabel(
        tag as ContentTagDto | string,
      ),
    )
    .filter(
      (tag): tag is string =>
        tag.length > 0,
    );

  return (
    <Link
      to={`/contents/${recommendation.contentId}`}
      className="
        group
        relative
        flex
        w-[260px]
        flex-shrink-0
        flex-col
        overflow-hidden
        rounded-2xl
        border
        border-gray-800
        bg-gray-900
        text-left
        transition-all
        duration-200
        hover:-translate-y-1
        hover:border-gray-700
        hover:bg-gray-800
        hover:shadow-xl
      "
    >
      <div
        className="
          relative
          aspect-[260/390]
          w-full
          overflow-hidden
          bg-gray-800
        "
      >
        {hasThumbnail ? (
          <>
            {!imageLoaded && (
              <div
                className="
                  absolute
                  inset-0
                  animate-pulse
                  bg-gray-800
                "
              />
            )}

            <img
              src={
                recommendation.thumbnailUrl
                ?? undefined
              }
              alt=""
              className={[
                'h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]',
                imageLoaded
                  ? 'opacity-100'
                  : 'opacity-0',
              ].join(' ')}
              onLoad={() =>
                setImageLoaded(true)
              }
              onError={() => {
                setImageError(true);
                setImageLoaded(true);
              }}
            />

            <div
              className="
                pointer-events-none
                absolute
                inset-0
                bg-gradient-to-t
                from-black/40
                via-transparent
                to-transparent
              "
            />
          </>
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
            <ImageOff className="h-9 w-9" />

            <span className="text-body3-m">
              썸네일 없음
            </span>
          </div>
        )}
      </div>

      <div
        className="
          flex
          flex-1
          flex-col
          p-5
        "
      >
        <div
          className="
            relative
            flex
            items-center
            justify-between
            gap-3
          "
        >
          <div
            className="
              flex
              items-center
              gap-1.5
              rounded-full
              border
              border-gray-700
              bg-gray-800
              px-2.5
              py-1.5
              text-body3-m
              text-gray-300
            "
          >
            <ContentTypeIcon
              type={recommendation.type}
            />

            <span>
              {
                CONTENT_TYPE_LABEL[
                  recommendation.type
                ]
              }
            </span>
          </div>

          {recommendation.externalRating
            != null && (
              <div
                className="
                  flex
                  items-center
                  gap-1
                "
              >
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
                    text-body3-m
                    text-gray-200
                  "
                >
                  {recommendation
                    .externalRating
                    .toFixed(1)}
                </span>

                {voteCount && (
                  <span
                    className="
                      text-body3-m
                      text-gray-500
                    "
                  >
                    ({voteCount})
                  </span>
                )}
              </div>
            )}
        </div>

        <div className="relative mt-4">
          <h3
            title={recommendation.title}
            className="
              line-clamp-2
              text-xl
              font-bold
              leading-snug
              text-gray-50
              transition-colors
              group-hover:text-white
            "
          >
            {recommendation.title}
          </h3>
        </div>

        <div
          className="
            relative
            mt-4
            flex
            min-h-[125px]
            flex-1
            flex-col
            rounded-xl
            border
            border-gray-800
            bg-black/20
            p-3.5
          "
        >
          <div
            className="
              mb-2
              flex
              items-center
              gap-1.5
            "
          >
            <Sparkles
              className="
                h-4
                w-4
                text-pink-400
              "
            />

            <span
              className="
                text-body3-m
                font-semibold
                text-pink-300
              "
            >
              AI 추천 이유
            </span>
          </div>

          <p
            className="
              line-clamp-4
              text-body2-m
              leading-relaxed
              text-gray-300
            "
          >
            {recommendation.reason}
          </p>
        </div>

        {displayTags.length > 0 && (
          <div
            className="
              relative
              mt-4
              flex
              h-[26px]
              min-w-0
              gap-1.5
              overflow-hidden
            "
          >
            {displayTags
              .slice(0, 2)
              .map((tag, index) => (
                <span
                  key={`${tag}-${index}`}
                  title={tag}
                  className="
                    inline-flex
                    h-[26px]
                    max-w-[calc(50%-3px)]
                    flex-shrink-0
                    items-center
                    overflow-hidden
                    rounded-full
                    bg-gray-800
                    px-2
                    text-caption1-m
                    text-gray-400
                  "
                >
                  <span
                    className="
                      min-w-0
                      truncate
                      leading-none
                    "
                  >
                    #{tag}
                  </span>
                </span>
              ))}
          </div>
        )}
      </div>
    </Link>
  );
}
