package com.mopl.recommendation.listener;

import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mopl.core.common.event.RecommendationPreferenceChangedEvent;
import com.mopl.recommendation.service.RecommendationCacheService;

@ExtendWith(MockitoExtension.class)
class RecommendationPreferenceChangedEventListenerTest {

    @Mock
    private RecommendationCacheService recommendationCacheService;

    private RecommendationPreferenceChangedEventListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new RecommendationPreferenceChangedEventListener(
                        recommendationCacheService
                );
    }

    @Test
    void evictsRecommendationCacheWhenPreferenceChanges() {
        UUID userId =
                UUID.randomUUID();

        RecommendationPreferenceChangedEvent event =
                new RecommendationPreferenceChangedEvent(
                        userId
                );

        listener.handle(event);

        verify(recommendationCacheService)
                .evict(userId);
    }
}