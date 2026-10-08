package com.mopl.recommendation.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mopl.recommendation.dto.RecommendationCandidateResult;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.dto.RecommendationResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecommendationCacheService recommendationCacheService;
    private final RecommendationCandidateService recommendationCandidateService;
    private final RecommendationRerankService recommendationRerankService;
    private final ColdStartRecommendationService coldStartRecommendationService;

    public List<RecommendationItem> getRecommendations(UUID userId) {
        return getRecommendationResult(userId).items();
    }

    public RecommendationResult getRecommendationResult(UUID userId) {
        // 1. Redis Cache HIT이면 AI/DB/ES 작업 없이 즉시 반환
        Optional<RecommendationResult> cachedRecommendations =
                recommendationCacheService.get(userId);

        if (cachedRecommendations.isPresent()) {
            return cachedRecommendations.get();
        }

        // 2. 사용자 취향 계산 + Semantic Candidate 생성
        RecommendationCandidateResult candidateResult =
                recommendationCandidateService.findCandidates(userId);

        RecommendationResult result;

        // 3. Cold Start 또는 AI fallback은 인기 추천을 반환
        if (candidateResult.coldStart()) {
            List<RecommendationItem> recommendations =
                    coldStartRecommendationService.recommend(
                            candidateResult.interactedContentIds()
                    );

            result = RecommendationResult.popular(recommendations);
        } else {
            // 4. 개인화 사용자는 후보를 LLM으로 rerank
            List<RecommendationItem> recommendations =
                    recommendationRerankService.rerank(
                    candidateResult.preferenceText(),
                    candidateResult.candidates()
            );

            result = RecommendationResult.personalized(recommendations);
        }

        // 5. 최종 결과를 Redis에 6시간 저장
        if (!result.items().isEmpty() && candidateResult.cacheable()) {
            recommendationCacheService.save(userId, result);
        }

        return result;
    }
}
