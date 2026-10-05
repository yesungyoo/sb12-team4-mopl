package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.*;

import com.mopl.realtime.moderation.dto.SanctionLevel;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore;
import com.mopl.realtime.moderation.rule.ProfanityRule;
import com.mopl.realtime.moderation.service.MessageModerationService;
import com.mopl.realtime.moderation.service.ModerationLogService;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 로컬 테스트 Redis를 선택적으로 사용하며, 전체 데이터를 삭제하거나 실제 사용자 키를 조회하지 않는다. */
@EnabledIfEnvironmentVariable(named = "MODERATION_TEST_REDIS_PORT", matches = "[0-9]+")
class ChatRestrictionStoreExternalRedisTest {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static ChatRestrictionStore store;

    @BeforeAll static void setup() {
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("MODERATION_TEST_REDIS_PORT")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        store = new ChatRestrictionStore(redis, ModerationTestSettings.defaults());
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }

    @ParameterizedTest @EnumSource(SanctionLevel.class)
    void acceptedDecisionAppliesAllExistingLevels(SanctionLevel level) throws Exception {
        UUID user = UUID.randomUUID();
        String token = store.claimReview(user);
        var decisionDeadline = Instant.now().plusSeconds(1);
        var observation = new ReviewObservation(decisionDeadline);
        var inbox = new SanctionDecisionInbox(decisionDeadline, observation);
        assertThat(new SanctionTool(inbox, observation).applyChatRestriction(level, "사유")).isTrue();
        var decision = inbox.await();
        assertThat(store.apply(user, token, decision.level(), decision.reason(), Instant.now().plusSeconds(1))).isTrue();
        assertThat(store.restricted(user)).isEqualTo(level != SanctionLevel.NONE);
        if (level != SanctionLevel.NONE) {
            long expected = level == SanctionLevel.TEMPORARY_SHORT ? 600 : 86400;
            assertThat(redis.getExpire("moderation:{" + user + "}:restriction")).isBetween(expected - 5, expected);
        }
    }
    @Test void wrongTokenExpiredApplyAndExpiredLeaseAreRejected() {
        UUID user = UUID.randomUUID();
        String token = store.claimReview(user);
        assertThat(store.apply(user, "wrong", SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(1))).isFalse();
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().minusSeconds(1))).isFalse();
        // 임대 만료를 재현하기 위해 이 테스트가 생성한 임의 사용자의 심사 키만 삭제한다.
        redis.delete("moderation:{" + user + "}:review");
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(1))).isFalse();
        assertThat(store.restricted(user)).isFalse();
    }
    @Test void concurrentApplyOnlyWritesOneDecision() throws Exception {
        UUID user = UUID.randomUUID();
        String token = store.claimReview(user);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 12; i++) pool.submit(() -> {
                start.await();
                if (store.apply(user, token, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(5))) {
                    accepted.incrementAndGet();
                }
                return null;
            });
            start.countDown();
        }
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(store.restricted(user)).isTrue();
    }
    @Test void newReviewCannotShortenExistingRestrictionOrUseOldToken() {
        UUID user = UUID.randomUUID();
        String oldToken = store.claimReview(user);
        assertThat(store.apply(user, oldToken, SanctionLevel.TEMPORARY_LONG, "사유", Instant.now().plusSeconds(1))).isTrue();
        redis.delete("moderation:{" + user + "}:review");
        String newToken = store.claimReview(user);
        assertThat(store.apply(user, oldToken, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(1))).isFalse();
        assertThat(store.apply(user, newToken, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(1))).isTrue();
        assertThat(redis.getExpire("moderation:{" + user + "}:restriction")).isBetween(86395L, 86400L);
    }
    @Test void actualRedisExpiryIsReportedAndExpiredRestrictionAllowsSendingAgain() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "moderation:{" + user + "}:restriction";
        assertThat(store.restriction(user)).isNull();
        var moderation = new MessageModerationService(store,
            org.mockito.Mockito.mock(ProfanityRule.class), org.mockito.Mockito.mock(MessageReviewService.class),
            org.mockito.Mockito.mock(ModerationLogService.class), org.mockito.Mockito.mock(ModerationReviewDispatcher.class),
            ModerationTestSettings.defaults(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThatCode(() -> moderation.assertCanSend(user)).doesNotThrowAnyException();
        String token = store.claimReview(user);
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_LONG, "문맥상 반복 공격", Instant.now().plusSeconds(5))).isTrue();
        Long expires = redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), java.util.List.of(key));
        var exception = catchThrowableOfType(() -> moderation.assertCanSend(user), ModerationException.class);
        var response = new com.mopl.realtime.moderation.handler.ModerationErrorHandler().handle(exception);
        assertThat(response.code()).isEqualTo("CHAT_RESTRICTED");
        assertThat(response.restrictionLevel()).isEqualTo(SanctionLevel.TEMPORARY_LONG);
        assertThat(response.restrictedUntil()).isEqualTo(Instant.ofEpochMilli(expires));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.path("code").asText()).isEqualTo("CHAT_RESTRICTED");
        assertThat(json.path("restrictionLevel").asText()).isEqualTo("TEMPORARY_LONG");
        assertThat(Instant.parse(json.path("restrictedUntil").asText())).isEqualTo(Instant.ofEpochMilli(expires));
        assertThat(json.toString()).doesNotContain("문맥상 반복 공격");
        // 테스트 사용자 키의 실제 Redis 만료를 앞당겨 만료 후 전송 허용을 확인한다.
        redis.expireAt(key, Instant.now().minusMillis(1));
        assertThat(store.restriction(user)).isNull();
        assertThatCode(() -> moderation.assertCanSend(user)).doesNotThrowAnyException();
    }

    @Test void oneRedisSanctionBlocksBothChatAndDmBeforePersistence() {
        UUID user = UUID.randomUUID();
        String token = store.claimReview(user);
        assertThat(store.apply(user, token, SanctionLevel.TEMPORARY_SHORT, "사유", Instant.now().plusSeconds(1))).isTrue();
        var properties = ModerationTestSettings.defaults();
        var moderation = new MessageModerationService(store, new ProfanityRule(properties, com.mopl.realtime.moderation.support.MessageReviewTestSettings.defaults()),
            org.mockito.Mockito.mock(MessageReviewService.class),
            org.mockito.Mockito.mock(ModerationLogService.class), org.mockito.Mockito.mock(ModerationReviewDispatcher.class),
            properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var chat = new com.mopl.realtime.contentchat.service.ContentChatService(
            org.mockito.Mockito.mock(com.mopl.realtime.contentchat.repository.ContentRepository.class),
            org.mockito.Mockito.mock(com.mopl.realtime.contentchat.repository.UserRepository.class),
            org.mockito.Mockito.mock(com.mopl.realtime.contentchat.repository.ContentChatMessageRepository.class), moderation);
        var dm = new com.mopl.realtime.directmessage.service.DirectMessageService(
            org.mockito.Mockito.mock(com.mopl.realtime.directmessage.repository.ConversationRepository.class),
            org.mockito.Mockito.mock(com.mopl.realtime.directmessage.repository.DirectMessageRepository.class), moderation);
        var state = store.restriction(user);
        assertThat(state.level()).isEqualTo(SanctionLevel.TEMPORARY_SHORT);
        assertThatThrownBy(() -> chat.send(UUID.randomUUID(), user, "정상 메시지"))
            .isInstanceOf(ModerationException.class).extracting(exception -> ((ModerationException) exception).getErrorCode())
            .isEqualTo(ModerationException.ErrorCode.CHAT_RESTRICTED);
        assertThatThrownBy(() -> dm.send(UUID.randomUUID(), user, "정상 메시지"))
            .isInstanceOf(ModerationException.class).extracting(exception -> ((ModerationException) exception).getErrorCode())
            .isEqualTo(ModerationException.ErrorCode.CHAT_RESTRICTED);
        var exception = catchThrowableOfType(() -> dm.send(UUID.randomUUID(), user, "정상 메시지"), ModerationException.class);
        var response = new com.mopl.realtime.moderation.handler.ModerationErrorHandler().handle(exception);
        assertThat(response.code()).isEqualTo("CHAT_RESTRICTED");
        assertThat(response.restrictionLevel()).isEqualTo(state.level());
        assertThat(response.restrictedUntil()).isEqualTo(state.restrictedUntil());
    }
}
