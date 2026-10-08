package com.mopl.recommendation.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.mopl.recommendation.config.RecommendationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.recommendation.dto.RecommendationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationCacheService {

    private static final String CACHE_KEY_PREFIX = "recommendations:";
    private static final String CACHE_KEY_VERSION = ":v3";

    private static final TypeReference<RecommendationResult> RECOMMENDATION_RESULT_TYPE =
            new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RecommendationProperties recommendationProperties;

    public Optional<RecommendationResult> get(UUID userId) {
        String key = createKey(userId);

        try {
            String cachedValue = redisTemplate.opsForValue()
                    .get(key);

            if (!StringUtils.hasText(cachedValue)) {
                return Optional.empty();
            }

            RecommendationResult recommendationResult = objectMapper.readValue(
                    cachedValue,
                    RECOMMENDATION_RESULT_TYPE
            );

            return Optional.of(recommendationResult);
        } catch (JsonProcessingException e) {
            // 손상되었거나 이전 스키마의 캐시는 제거하고 다음 요청에서 새 추천 생성
            log.warn("개인화 추천 캐시 역직렬화에 실패했습니다. key={}", key, e);

            deleteQuietly(key);

            return Optional.empty();
        } catch (DataAccessException e) {
            // Redis 장애 때문에 추천 API 전체를 실패시키지 않는다.
            log.warn("개인화 추천 캐시 조회에 실패했습니다. key={}", key, e);

            return Optional.empty();
        }
    }

    public void save(UUID userId, RecommendationResult recommendationResult) {
        if (recommendationResult == null
                || recommendationResult.items().isEmpty()) {
            return;
        }

        String key = createKey(userId);

        try {
            String value = objectMapper.writeValueAsString(recommendationResult);

            redisTemplate.opsForValue().set(
                    key,
                    value,
                    recommendationProperties.cacheTtl()
            );
        } catch (JsonProcessingException e) {
            log.warn("개인화 추천 캐시 직렬화에 실패했습니다. key={}", key, e);
        } catch (DataAccessException e) {
            log.warn("개인화 추천 캐시 저장에 실패했습니다. key={}", key, e);
        }
    }

    // 사용자의 추천 취향 데이터 변경 시 기존 추천 결과 무효화
    public void evict(UUID userId) {
        deleteQuietly(createKey(userId));
    }

    private String createKey(UUID userId) {
        return CACHE_KEY_PREFIX + userId + CACHE_KEY_VERSION;
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.warn("손상된 개인화 추천 캐시 삭제에 실패했습니다. key={}", key, e);
        }
    }
}
