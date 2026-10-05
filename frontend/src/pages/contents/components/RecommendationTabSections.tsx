import {
  useEffect,
  useState,
} from 'react';

import { getRecommendationSections } from '@/lib/api/recommendations';
import type {
  ContentTagDto,
  RecommendationSection,
  RecommendationSectionItem,
  RecommendationTab,
} from '@/lib/types';

import ContentRailSection, {
  type SectionContentItem,
} from './ContentRailSection';
import RecommendationSectionComponent from './RecommendationSection';

interface RecommendationTabSectionsProps {
  tab: RecommendationTab;
}

function getTagLabel(tag: ContentTagDto) {
  return tag.value || tag.tag;
}

function getCreatedAtBadge(createdAt: string) {
  const createdDate = new Date(createdAt);

  if (Number.isNaN(createdDate.getTime())) {
    return '신규';
  }

  const today = new Date();

  const todayStart = new Date(
    today.getFullYear(),
    today.getMonth(),
    today.getDate(),
  );

  const createdStart = new Date(
    createdDate.getFullYear(),
    createdDate.getMonth(),
    createdDate.getDate(),
  );

  const differenceInDays = Math.floor(
    (
      todayStart.getTime()
      - createdStart.getTime()
    )
    / (1000 * 60 * 60 * 24),
  );

  if (differenceInDays <= 0) {
    return '오늘 추가';
  }

  if (differenceInDays === 1) {
    return '1일 전';
  }

  return `${differenceInDays}일 전`;
}

function toSectionContentItem(
  item: RecommendationSectionItem,
  showCreatedAtBadge: boolean,
): SectionContentItem {
  return {
    contentId: item.contentId,
    title: item.title,
    thumbnailUrl: item.thumbnailUrl,
    type: item.type,
    rating: item.averageRating,
    tags: item.tags
      .map(getTagLabel)
      .filter(Boolean),
    badge: showCreatedAtBadge
      ? getCreatedAtBadge(item.createdAt)
      : undefined,
  };
}

function isNewSection(
  tab: RecommendationTab,
  section: RecommendationSection,
) {
  return tab === 'NEW'
    || section.key.startsWith('NEW_');
}

function isPersonalizedSection(
  section: RecommendationSection,
) {
  return section.key === 'NEW_FOR_YOU'
    || section.key.startsWith('PERSONALIZED_');
}

export default function RecommendationTabSections({
  tab,
}: RecommendationTabSectionsProps) {
  const [sections, setSections] =
    useState<RecommendationSection[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;

    const fetchSections = async () => {
      try {
        setLoading(true);
        setError(undefined);

        const response =
          await getRecommendationSections(tab);

        if (!cancelled) {
          setSections(response.sections);
        }
      } catch (fetchError) {
        console.error(fetchError);

        if (!cancelled) {
          setSections([]);

          setError(
            fetchError instanceof Error
              ? fetchError.message
              : '추천 섹션을 불러오지 못했습니다.',
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void fetchSections();

    return () => {
      cancelled = true;
    };
  }, [
    reloadKey,
    tab,
  ]);

  if (loading) {
    return (
      <div className="flex min-w-0 flex-col gap-12">
        {Array.from({ length: 3 }).map((_, index) => (
          <RecommendationRailSkeleton key={index} />
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="
          rounded-2xl
          border
          border-gray-800
          bg-gray-900/40
          px-6
          py-8
        "
      >
        <p className="text-body2-m text-gray-400">
          추천 콘텐츠를 불러오지 못했어요.
        </p>

        <button
          type="button"
          onClick={() =>
            setReloadKey((current) => current + 1)
          }
          className="
            mt-4
            rounded-lg
            bg-gray-800
            px-4
            py-2
            text-body3-b
            text-gray-200
            transition
            hover:bg-gray-700
          "
        >
          다시 시도
        </button>
      </div>
    );
  }

  if (sections.length === 0) {
    return null;
  }

  return (
    <div className="flex min-w-0 flex-col gap-12">
      {sections.map((section) => {
        if (section.key === 'AI_PERSONALIZED') {
          return (
            <RecommendationSectionComponent
              key={section.key}
              title={section.title}
              description={section.description}
            />
          );
        }

        if (section.items.length === 0) {
          return null;
        }

        return (
          <ContentRailSection
            key={section.key}
            title={section.title}
            description={section.description}
            contentType={section.contentType}
            tag={section.tag}
            personalized={isPersonalizedSection(section)}
            items={section.items.map((item) =>
              toSectionContentItem(
                item,
                isNewSection(tab, section),
              ),
            )}
          />
        );
      })}
    </div>
  );
}

function RecommendationRailSkeleton() {
  return (
    <section className="min-w-0 animate-pulse">
      <div className="mb-5 flex items-start gap-3">
        <div className="h-9 w-9 flex-shrink-0 rounded-xl bg-gray-900" />

        <div>
          <div className="h-6 w-52 rounded bg-gray-900" />
          <div className="mt-2 h-4 w-80 max-w-full rounded bg-gray-900" />
        </div>
      </div>

      <div className="flex gap-4 overflow-hidden">
        {Array.from({ length: 5 }).map((_, index) => (
          <div
            key={index}
            className="w-[200px] flex-shrink-0"
          >
            <div className="aspect-[260/390] w-full rounded-2xl bg-gray-900" />
            <div className="mt-3 h-5 w-4/5 rounded bg-gray-900" />
            <div className="mt-2 h-4 w-16 rounded bg-gray-900" />
          </div>
        ))}
      </div>
    </section>
  );
}
