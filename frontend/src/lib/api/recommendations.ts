import type {
  RecommendationItem,
  RecommendationSectionsResponse,
  RecommendationTab,
} from '@/lib/types';

import apiClient from './client';

// [기존] AI 개인화 추천 조회
export const getRecommendations = async (): Promise<RecommendationItem[]> => {
  const response = await apiClient.get<RecommendationItem[]>(
    '/api/recommendations',
  );

  return response.data;
};

// [추가] 탭별 추천 섹션 조회
export const getRecommendationSections = async (
  tab: RecommendationTab,
): Promise<RecommendationSectionsResponse> => {
  const response = await apiClient.get<RecommendationSectionsResponse>(
    '/api/recommendations/sections',
    {
      params: {
        tab,
      },
    },
  );

  return response.data;
};