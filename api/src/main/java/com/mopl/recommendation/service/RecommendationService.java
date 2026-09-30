package com.mopl.recommendation.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mopl.recommendation.dto.RecommendationCandidateResult;
import com.mopl.recommendation.dto.RecommendationItem;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecommendationCacheService recommendationCacheService;
    private final RecommendationCandidateService recommendationCandidateService;
    private final RecommendationRerankService recommendationRerankService;
    private final ColdStartRecommendationService coldStartRecommendationService;

    public List<RecommendationItem> getRecommendations(UUID userId) {
        // 1. Redis Cache HIT이면 AI/DB/ES 작업 없이 즉시 반환
        Optional<List<RecommendationItem>> cachedRecommendations =
                recommendationCacheService.get(userId);

        if (cachedRecommendations.isPresent()) {
            return cachedRecommendations.get();
        }

        // 2. 사용자 취향 계산 + Semantic Candidate 생성
        RecommendationCandidateResult candidateResult =
                recommendationCandidateService.findCandidates(userId);

        List<RecommendationItem> recommendations;

        // 3. 활동 이력이 없는 신규 사용자는 AI를 호출하지 않음
        if (candidateResult.coldStart()) {
            recommendations = coldStartRecommendationService.recommend();
        } else {
            // 4. 개인화 사용자는 후보를 LLM으로 rerank
            recommendations = recommendationRerankService.rerank(
                    candidateResult.preferenceText(),
                    candidateResult.candidates()
            );
        }

        // 5. 최종 결과를 Redis에 6시간 저장
        recommendationCacheService.save(userId, recommendations);

        return recommendations;
    }
}
