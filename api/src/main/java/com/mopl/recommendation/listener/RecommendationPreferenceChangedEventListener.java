package com.mopl.recommendation.listener;

import com.mopl.core.common.event.RecommendationPreferenceChangedEvent;
import com.mopl.recommendation.service.RecommendationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class RecommendationPreferenceChangedEventListener {

    private final RecommendationCacheService recommendationCacheService;

    // DB 변경이 실제 commit된 경우에만 추천 캐시 제거
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(RecommendationPreferenceChangedEvent event) {
        recommendationCacheService.evict(event.userId());
    }
}
