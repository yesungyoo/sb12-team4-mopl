package com.mopl.auth.redis;

import com.mopl.infrastructure.security.jwt.JwtProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.mopl.infrastructure.security.token.AccessTokenInvalidationService;

/**
 * NOTE: StringRedisTemplate 빈은 infrastructure 모듈에서 이미 제공한다고 가정합니다.
 * (아키텍처상 Redis 연동은 infrastructure 담당)
 */
@Service
@RequiredArgsConstructor
public class TokenRedisService {

    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";

    private final AccessTokenInvalidationService accessTokenInvalidationService;
    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    // ===================== Refresh Token =====================

    public void saveRefreshToken(UUID userId, String refreshToken) {
        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + userId,
                refreshToken,
                Duration.ofSeconds(jwtProperties.refreshTokenExpirationSeconds())
        );
    }

    public boolean isValidRefreshToken(UUID userId, String refreshToken) {
        String saved = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        return saved != null && saved.equals(refreshToken);
    }

    public void deleteRefreshToken(UUID userId) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
    }

    // ===================== 강제 로그아웃 (토큰 무효화) =====================

    /**
     * 지금 이 시각 이전에 발급된 access token 은 전부 무효로 처리한다.
     * 잠금 / 권한변경 / 탈퇴 / 로그아웃 시 호출.
     * TTL 은 access token 최대 수명만큼만 유지.
     */
    public void invalidateTokensIssuedBefore(UUID userId, Instant now) {
        accessTokenInvalidationService.invalidateTokensIssuedBefore(userId, now);
    }

    /** issuedAt 시점에 발급된 토큰이 이후 무효화 처리 대상인지 확인 */
    public boolean isInvalidated(UUID userId, Instant issuedAt) {
        return accessTokenInvalidationService.isInvalidated(userId, issuedAt);
    }
}
