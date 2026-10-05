package com.mopl.realtime.moderation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.realtime.moderation.dto.SanctionLevel;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class ChatRestrictionStoreIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static ChatRestrictionStore store;
    @BeforeAll static void setup() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        store = new ChatRestrictionStore(redis, ModerationTestSettings.defaults());
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }

    @Test void onlyOneConcurrentReviewAndSanctionCanWin() throws Exception {
        UUID user = UUID.randomUUID();
        var winners = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 20; i++) pool.submit(() -> {
                String token = store.claimReview(user); if (token != null) winners.add(token);
            });
        }
        assertThat(winners).hasSize(1);
        String token = winners.element();
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(10))).isTrue();
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_LONG, "사유", Instant.now().plusSeconds(10))).isFalse();
        assertThat(store.restricted(user)).isTrue();
    }
    @Test void noneDoesNotRestrictAndShortLongHaveServerControlledTtl() {
        for (var level : SanctionLevel.values()) {
            UUID user = UUID.randomUUID(); String token = store.claimReview(user);
            assertThat(store.apply(user, token, level, "사유", Instant.now().plusSeconds(10))).isTrue();
            assertThat(store.restricted(user)).isEqualTo(level != SanctionLevel.NONE);
            if (level != SanctionLevel.NONE) {
                long expected = level == SanctionLevel.TEMPORARY_SHORT ? 600 : 86400;
                assertThat(redis.getExpire("moderation:{" + user + "}:restriction")).isBetween(expected - 5, expected);
            }
        }
    }
    @Test void expiredDeadlineAndWrongTokenCannotRestrict() {
        UUID user = UUID.randomUUID(); String token = store.claimReview(user);
        assertThat(store.apply(user, "wrong", SanctionLevel.TEMPORARY_LONG, "사유", Instant.now().plusSeconds(10))).isFalse();
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_LONG, "사유", Instant.now().minusSeconds(1))).isFalse();
        assertThat(store.restricted(user)).isFalse();
    }
}
