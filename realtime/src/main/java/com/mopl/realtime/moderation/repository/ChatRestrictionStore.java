package com.mopl.realtime.moderation.repository;

import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.dto.SanctionLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatRestrictionStore {
    private final StringRedisTemplate redis;
    private final ModerationProperties properties;
    private static String prefix(UUID userId) { return "moderation:{" + userId + "}:"; }

    public boolean restricted(UUID userId) { return Boolean.TRUE.equals(redis.hasKey(prefix(userId) + "restriction")); }
    // 값과 절대 만료 시각을 같은 Redis 실행에서 읽어 서버 시계 차이나 조회 경쟁을 피한다.
    private static final DefaultRedisScript<List> STATUS = new DefaultRedisScript<>("""
        local value = redis.call('GET', KEYS[1])
        if not value then return {} end
        local expires = redis.call('PEXPIRETIME', KEYS[1])
        return {value, expires}
        """, List.class);

    public Restriction restriction(UUID userId) {
        List<?> result = redis.execute(STATUS, List.of(prefix(userId) + "restriction"));
        if (result == null || result.isEmpty()) return null;
        String value = result.get(0).toString();
        int separator = value.indexOf(':');
        SanctionLevel level = SanctionLevel.valueOf(separator < 0 ? value : value.substring(0, separator));
        long expires = ((Number) result.get(1)).longValue();
        if (expires < 0 || level == SanctionLevel.NONE) throw new IllegalStateException("Invalid restriction state");
        return new Restriction(level, Instant.ofEpochMilli(expires));
    }

    public record Restriction(SanctionLevel level, Instant restrictedUntil) {}

    public String claimReview(UUID userId) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(prefix(userId) + "review", token,
            properties.reviewCooldown())) ? token : null;
    }

    // 동일 사용자/심사에 대한 중복 Tool 호출과 만료된 심사의 제재를 원자적으로 거부한다.
    private static final DefaultRedisScript<Long> APPLY = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        local now = redis.call('TIME')
        if tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) >= tonumber(ARGV[4]) then return 0 end
        if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
        local lease = redis.call('PTTL', KEYS[1])
        if lease <= 0 then return 0 end
        redis.call('SET', KEYS[2], ARGV[2], 'PX', lease)
        local duration = tonumber(ARGV[3])
        if duration > 0 and redis.call('PTTL', KEYS[3]) < duration then
          redis.call('SET', KEYS[3], ARGV[2], 'PX', duration)
        end
        return 1
        """, Long.class);

    public boolean apply(UUID userId, String token, SanctionLevel level, String reason, Instant deadline) {
        Duration duration = switch (level) {
            case NONE -> Duration.ZERO;
            case TEMPORARY_SHORT -> properties.shortRestriction();
            case TEMPORARY_LONG -> properties.longRestriction();
        };
        String base = prefix(userId);
        return Long.valueOf(1).equals(redis.execute(APPLY,
            List.of(base + "review", base + "decision:" + token, base + "restriction"),
            token, level.name() + ":" + reason, String.valueOf(duration.toMillis()), String.valueOf(deadline.toEpochMilli())));
    }
}
