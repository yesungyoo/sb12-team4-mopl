package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.dto.SanctionLevel;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore;
import com.mopl.realtime.moderation.service.ModerationContextService;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModerationReviewDispatcherTest {
    private final ChatRestrictionStore store = mock(ChatRestrictionStore.class);
    private final ModerationContextService contexts = mock(ModerationContextService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService reviews = Executors.newFixedThreadPool(2);
    private final ExecutorService llm = Executors.newFixedThreadPool(2);
    private final UUID user = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @AfterEach void close() { reviews.shutdownNow(); llm.shutdownNow(); meters.close(); }
    private ModerationReviewDispatcher dispatcher(ModerationReviewer reviewer, Duration timeout) {
        return dispatcher(reviewer, ModerationTestSettings.withTimeout(timeout));
    }
    private ModerationReviewDispatcher dispatcher(ModerationReviewer reviewer, ModerationProperties settings) {
        when(contexts.load(user, MessageType.DM, target, "씨발")).thenReturn(
            new ModerationContextService.ReviewContext(user, MessageType.DM, "씨발", List.of(), List.of()));
        return new ModerationReviewDispatcher(store, contexts, reviewer, settings, meters, reviews, llm);
    }
    private void finish() throws Exception {
        reviews.shutdown(); assertThat(reviews.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
    }
    @Test void apiFailureStaysInReviewAndDoesNotPropagateToSender() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        var dispatcher = dispatcher((context, tool) -> { throw new IllegalStateException("API unavailable"); }, Duration.ofSeconds(1));
        dispatcher.submit(user, MessageType.DM, target, "씨발");
        finish();
        assertThat(meters.get("moderation.reviews").tag("outcome", "error").counter().count()).isEqualTo(1);
        verify(store, never()).apply(any(), any(), any(), any(), any());
    }
    @Test void timeoutIsMeasuredAndLateToolCannotApplySanction() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseLateResponse = new CountDownLatch(1);
        CountDownLatch lateToolFinished = new CountDownLatch(1);
        var dispatcher = dispatcher((context, tool) -> {
            started.countDown();
            awaitIgnoringInterrupts(releaseLateResponse);
            assertThat(tool.applyChatRestriction(SanctionLevel.TEMPORARY_LONG, "반복 위반")).isFalse();
            lateToolFinished.countDown();
        }, Duration.ofSeconds(1));
        try {
            dispatcher.submit(user, MessageType.DM, target, "씨발");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            finish();
            releaseLateResponse.countDown();
            assertThat(lateToolFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(meters.get("moderation.reviews").tag("outcome", "timeout").counter().count()).isEqualTo(1);
            verify(store, never()).apply(any(), any(), any(), any(), any());
        } finally { releaseLateResponse.countDown(); }
    }
    @ParameterizedTest @EnumSource(SanctionLevel.class)
    void acceptedDecisionAppliesEveryLevelAndPreservesCounters(SanctionLevel level) throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        when(store.apply(eq(user), eq("token"), eq(level), eq("사유"), any())).thenReturn(true);
        var dispatcher = dispatcher((context, tool) -> {
            assertThat(tool.applyChatRestriction(level, "사유")).isTrue();
            assertThat(tool.applyChatRestriction(level, "중복")).isFalse();
        }, Duration.ofSeconds(2));
        dispatcher.submit(user, MessageType.DM, target, "씨발");
        finish();
        verify(store, times(1)).apply(eq(user), eq("token"), eq(level), eq("사유"), any());
        assertThat(meters.get("moderation.reviews").tag("outcome", "completed").counter().count()).isEqualTo(1);
        assertThat(meters.get("moderation.tool").tag("outcome", "applied").counter().count()).isEqualTo(1);
        assertThat(meters.get("moderation.sanctions").tag("level", level.name()).counter().count()).isEqualTo(1);
        assertThat(meters.get("moderation.decision").tag("outcome", "accepted").timer().count()).isEqualTo(1);
        assertThat(meters.get("moderation.apply").tag("outcome", "applied").timer().count()).isEqualTo(1);
    }
    @Test void acceptedDecisionDoesNotWaitForWorkerReturnOrInterruptWorker() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        when(store.apply(any(), any(), any(), any(), any())).thenReturn(true);
        var release = new CountDownLatch(1);
        var workerFinished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var dispatcher = dispatcher((context, tool) -> {
            tool.applyChatRestriction(SanctionLevel.TEMPORARY_SHORT, "사유");
            try { release.await(); } catch (InterruptedException exception) { interrupted.set(true); }
            workerFinished.countDown();
        }, Duration.ofSeconds(2));
        try {
            dispatcher.submit(user, MessageType.DM, target, "씨발");
            finish();
            assertThat(workerFinished.getCount()).isEqualTo(1);
            assertThat(interrupted.get()).isFalse();
            assertThat(meters.get("moderation.reviews").tag("outcome", "completed").counter().count()).isEqualTo(1);
            assertThat(meters.find("moderation.reviews").tag("outcome", "timeout").counter()).isNull();
        } finally { release.countDown(); }
        assertThat(workerFinished.await(5, TimeUnit.SECONDS)).isTrue();
    }
    @Test void redisApplicationCanFinishAfterJudgmentDeadline() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch finishApply = new CountDownLatch(1);
        when(store.apply(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat((Instant) invocation.getArgument(4)).isAfter(Instant.now());
            applyStarted.countDown();
            assertThat(finishApply.await(10, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        var defaults = ModerationTestSettings.withTimeout(Duration.ofSeconds(5));
        var settings = new ModerationProperties(defaults.violationWindow(), defaults.violationThreshold(),
            defaults.contextLimit(), defaults.reviewTimeout(), Duration.ofSeconds(15), defaults.reviewCooldown(),
            defaults.shortRestriction(), defaults.longRestriction(), defaults.workers(), defaults.queueCapacity(),
            defaults.prohibitedWords());
        var dispatcher = dispatcher((context, tool) -> tool.applyChatRestriction(SanctionLevel.NONE, "사유"), settings);
        Instant submittedAt = Instant.now();
        try {
            dispatcher.submit(user, MessageType.DM, target, "씨발");
            assertThat(applyStarted.await(8, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(8)).until(() ->
                Instant.now().isAfter(submittedAt.plus(settings.reviewTimeout()).plusMillis(100)));
            finishApply.countDown();
            finish();
            assertThat(meters.get("moderation.reviews").tag("outcome", "completed").counter().count()).isEqualTo(1);
            assertThat(meters.find("moderation.reviews").tag("outcome", "timeout").counter()).isNull();
        } finally { finishApply.countDown(); }
    }
    @Test void redisRejectionIsNotJudgmentTimeout() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        when(store.apply(any(), any(), any(), any(), any())).thenReturn(false);
        var dispatcher = dispatcher((context, tool) -> tool.applyChatRestriction(SanctionLevel.TEMPORARY_LONG, "사유"),
            Duration.ofSeconds(1));
        dispatcher.submit(user, MessageType.DM, target, "씨발");
        finish();
        assertThat(meters.get("moderation.reviews").tag("outcome", "no_decision").counter().count()).isEqualTo(1);
        assertThat(meters.get("moderation.tool").tag("outcome", "rejected").counter().count()).isEqualTo(1);
        assertThat(meters.find("moderation.sanctions").counter()).isNull();
        assertThat(meters.find("moderation.reviews").tag("outcome", "timeout").counter()).isNull();
    }
    @Test void redisExceptionIsApplicationErrorAndDecisionRemainsAccepted() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        when(store.apply(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("Redis unavailable"));
        var dispatcher = dispatcher((context, tool) -> tool.applyChatRestriction(SanctionLevel.TEMPORARY_LONG, "사유"),
            Duration.ofSeconds(1));
        dispatcher.submit(user, MessageType.DM, target, "씨발");
        finish();
        assertThat(meters.get("moderation.reviews").tag("outcome", "error").counter().count()).isEqualTo(1);
        assertThat(meters.get("moderation.apply").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(meters.get("moderation.decision").tag("outcome", "accepted").timer().count()).isEqualTo(1);
        assertThat(meters.find("moderation.reviews").tag("outcome", "timeout").counter()).isNull();
        assertThat(meters.find("moderation.tool").counter()).isNull();
    }
    @Test void cleanupFailureAfterAcceptanceDoesNotDiscardDecision() throws Exception {
        when(store.claimReview(user)).thenReturn("token");
        when(store.apply(any(), any(), any(), any(), any())).thenReturn(true);
        var dispatcher = dispatcher((context, tool) -> {
            tool.applyChatRestriction(SanctionLevel.NONE, "사유");
            throw new IllegalStateException("cleanup failure");
        }, Duration.ofSeconds(1));
        dispatcher.submit(user, MessageType.DM, target, "씨발");
        finish();
        assertThat(meters.get("moderation.reviews").tag("outcome", "completed").counter().count()).isEqualTo(1);
    }
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; }
            catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Test void concurrentAndSequentialRequestsUseOneReviewClaim() throws Exception {
        AtomicBoolean claimed = new AtomicBoolean();
        when(store.claimReview(user)).thenAnswer(call -> claimed.compareAndSet(false, true) ? "token" : null);
        var reviewer = mock(ModerationReviewer.class);
        var dispatcher = dispatcher(reviewer, Duration.ofSeconds(1));
        try (var callers = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 20; i++) callers.submit(() -> dispatcher.submit(user, MessageType.DM, target, "씨발"));
        }
        dispatcher.submit(user, MessageType.DM, target, "씨발");
        finish();
        verify(reviewer, times(1)).review(any(), any());
        assertThat(meters.get("moderation.reviews").tag("outcome", "suppressed").counter().count()).isEqualTo(20);
    }
}
