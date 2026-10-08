package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.core.common.enums.ContentType;
import com.mopl.recommendation.config.RecommendationProperties;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.dto.RecommendationResult;
import com.mopl.recommendation.dto.RecommendationResultType;

@ExtendWith(MockitoExtension.class)
class RecommendationCacheServiceTest {

    // [#10 수정]
    // 추천 결과 유형(PERSONALIZED/POPULAR)을 캐시에 포함해 캐시 버전을 v3로 변경
    private static final String CACHE_KEY_VERSION = ":v3";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;

    private RecommendationProperties recommendationProperties;

    private RecommendationCacheService recommendationCacheService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        recommendationProperties = new RecommendationProperties(
                30,
                10,
                Duration.ofHours(6)
        );

        recommendationCacheService = new RecommendationCacheService(
                redisTemplate,
                objectMapper,
                recommendationProperties
        );
    }

    @Test
    void returnsEmptyWhenCacheDoesNotExist() {
        UUID userId = UUID.randomUUID();

        String expectedKey = createExpectedKey(userId);

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(expectedKey))
                .thenReturn(null);

        Optional<RecommendationResult> result =
                recommendationCacheService.get(userId);

        assertThat(result).isEmpty();

        verify(valueOperations)
                .get(expectedKey);
    }

    @Test
    void returnsCachedRecommendations() throws Exception {
        UUID userId = UUID.randomUUID();

        RecommendationItem recommendation =
                createRecommendationItem();

        String expectedKey = createExpectedKey(userId);

        String cachedValue = objectMapper.writeValueAsString(
                RecommendationResult.popular(List.of(recommendation))
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(expectedKey))
                .thenReturn(cachedValue);

        Optional<RecommendationResult> result =
                recommendationCacheService.get(userId);

        assertThat(result).isPresent();

        assertThat(result.orElseThrow().items())
                .hasSize(1);

        assertThat(
                result.orElseThrow()
                        .items()
                        .getFirst()
                        .contentId()
        ).isEqualTo(
                recommendation.contentId()
        );

        assertThat(
                result.orElseThrow()
                        .items()
                        .getFirst()
                        .reason()
        ).isEqualTo(
                recommendation.reason()
        );

        assertThat(result.orElseThrow().type())
                .isEqualTo(RecommendationResultType.POPULAR);
    }

    @Test
    void savesRecommendationsWithSixHourTtl() throws Exception {
        UUID userId = UUID.randomUUID();

        RecommendationItem recommendation =
                createRecommendationItem();

        String expectedKey = createExpectedKey(userId);

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        recommendationCacheService.save(
                userId,
                RecommendationResult.personalized(
                        List.of(recommendation)
                )
        );

        verify(valueOperations)
                .set(
                        eq(expectedKey),
                        anyString(),
                        eq(Duration.ofHours(6))
                );
    }

    @Test
    void doesNotSaveEmptyRecommendations() {
        UUID userId = UUID.randomUUID();

        recommendationCacheService.save(
                userId,
                RecommendationResult.popular(List.of())
        );

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void treatsBrokenCacheAsCacheMiss() throws Exception {
        UUID userId = UUID.randomUUID();

        String expectedKey = createExpectedKey(userId);

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(expectedKey))
                .thenReturn("{this-is-not-valid-json");

        Optional<RecommendationResult> result =
                recommendationCacheService.get(userId);

        assertThat(result).isEmpty();

        verify(redisTemplate)
                .delete(expectedKey);
    }

    @Test
    void evictsRecommendationsForUser() {
        UUID userId = UUID.randomUUID();

        String expectedKey = createExpectedKey(userId);

        recommendationCacheService.evict(userId);

        verify(redisTemplate)
                .delete(expectedKey);
    }

    private RecommendationItem createRecommendationItem() {
        return new RecommendationItem(
                UUID.randomUUID(),
                "Interstellar",
                ContentType.MOVIE,
                List.of(
                        new ContentTagDto(
                                "GENRE",
                                "SF"
                        )
                ),
                0.95,
                8.7,
                120.0,
                5000L,
                "SF 콘텐츠를 선호하는 취향과 잘 맞습니다."
        );
    }

    // [#90 추가]
    // 테스트에서도 production 코드와 동일한 캐시 키 규칙을 한 곳에서 관리
    private String createExpectedKey(UUID userId) {
        return "recommendations:"
                + userId
                + CACHE_KEY_VERSION;
    }
}
