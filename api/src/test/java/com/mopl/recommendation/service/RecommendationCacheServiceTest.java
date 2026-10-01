package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

@ExtendWith(MockitoExtension.class)
class RecommendationCacheServiceTest {

    // [#90 추가]
    // RecommendationItem 응답 스키마에 thumbnailUrl이 추가되어 캐시 버전을 v2로 변경
    private static final String CACHE_KEY_VERSION = ":v2";

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

        Optional<List<RecommendationItem>> result =
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
                List.of(recommendation)
        );

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(expectedKey))
                .thenReturn(cachedValue);

        Optional<List<RecommendationItem>> result =
                recommendationCacheService.get(userId);

        assertThat(result).isPresent();

        assertThat(result.orElseThrow())
                .hasSize(1);

        assertThat(
                result.orElseThrow()
                        .getFirst()
                        .contentId()
        ).isEqualTo(
                recommendation.contentId()
        );

        assertThat(
                result.orElseThrow()
                        .getFirst()
                        .reason()
        ).isEqualTo(
                recommendation.reason()
        );
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
                List.of(recommendation)
        );

        verify(valueOperations)
                .set(
                        eq(expectedKey),
                        anyString(),
                        eq(Duration.ofHours(6))
                );
    }

    @Test
    void treatsBrokenCacheAsCacheMiss() throws Exception {
        UUID userId = UUID.randomUUID();

        String expectedKey = createExpectedKey(userId);

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.get(expectedKey))
                .thenReturn("{this-is-not-valid-json");

        Optional<List<RecommendationItem>> result =
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