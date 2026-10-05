package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.dto.MessageReviewContext;
import com.mopl.realtime.moderation.dto.MessageReviewDecision;
import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.exception.InvalidMessageReviewResponseException;
import com.mopl.realtime.moderation.service.ModerationContextService;
import com.mopl.realtime.moderation.service.ModerationLogService;
import com.mopl.realtime.moderation.support.MessageReviewTestSettings;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class MessageReviewServiceTest {
    private final ModerationContextService contexts = mock(ModerationContextService.class);
    private final ModerationLogService logs = mock(ModerationLogService.class);
    private final ModerationReviewDispatcher sanctions = mock(ModerationReviewDispatcher.class);
    private final UUID user = UUID.randomUUID(), target = UUID.randomUUID(), message = UUID.randomUUID();
    private final String text = "가정교육 수준 보인다";
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final TransactionTemplate tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    });

    @BeforeEach void setup() {
        when(contexts.loadMessage(user, MessageType.DM, target, message, text))
            .thenReturn(new MessageReviewContext(text, List.of()));
    }
    @AfterEach void close() throws InterruptedException {
        scheduler.shutdownNow(); executor.shutdownNow();
        assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        meters.close();
    }
    private MessageReviewService service(MessageReviewer reviewer, Duration timeout) {
        return new MessageReviewService(contexts, reviewer, new MessageReviewRoutingFilter(), logs, sanctions, ModerationTestSettings.defaults(),
            MessageReviewTestSettings.withTimeout(timeout), meters, executor, scheduler);
    }
    private void submit(MessageReviewService service) {
        submit(service, RuleAction.REVIEW, text);
    }
    private void submit(MessageReviewService service, RuleAction action, String messageText) {
        tx.executeWithoutResult(status -> service.reviewAfterCommit(user, MessageType.DM, target, message, messageText, action));
    }
    private void outcome(String value) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var counter = meters.find("moderation.message.reviews").tag("outcome", value).counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1);
        });
    }
    private void drain() throws Exception { executor.submit(() -> { }).get(8, TimeUnit.SECONDS); }

    @Test void routineObservationUsesDebugAndFailureObservationRemainsInfo() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReviewObservation.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);
            var observation = new ReviewObservation(Instant.now().plusSeconds(10));
            observation.event("message_review_started", "");
            observation.event("message_llm_started", "contextSize=2");
            observation.event("message_review_finished", "outcome=allow");
            observation.event("review_finished", "outcome=completed");
            assertThat(appender.list).hasSize(4);
            assertThat(appender.list).allMatch(event -> event.getLevel() == Level.DEBUG);

            observation.event("message_review_finished", "outcome=timeout");
            observation.event("review_finished", "outcome=error");
            assertThat(appender.list.subList(4, 6)).allMatch(event -> event.getLevel() == Level.INFO);
            assertThat(appender.list.toString()).doesNotContain("Authorization", "Bearer", "prompt", "API key");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test void noWorkBeforeCommitOrAfterRollbackOrOutsideTransaction() throws Exception {
        MessageReviewer reviewer = mock(MessageReviewer.class);
        var service = service(reviewer, Duration.ofSeconds(1));
        tx.executeWithoutResult(status -> {
            service.reviewAfterCommit(user, MessageType.DM, target, message, text, RuleAction.REVIEW);
            verifyNoInteractions(reviewer, contexts);
            status.setRollbackOnly();
        });
        service.reviewAfterCommit(user, MessageType.DM, target, message, text, RuleAction.REVIEW);
        drain();
        verifyNoInteractions(reviewer, contexts, logs, sanctions);
    }
    @Test void commitReturnsWhileLlmIsStillRunning() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = service(context -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return new MessageReviewDecision(MessageReviewDecision.Action.ALLOW);
        }, Duration.ofSeconds(2));
        try {
            submit(service);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
            verifyNoInteractions(logs, sanctions);
        } finally { release.countDown(); }
        drain(); outcome("allow");
    }
    @ParameterizedTest @EnumSource(MessageReviewDecision.Action.class)
    void validDecisionsAreSeparateFromFailuresAndOnlyViolationCounts(MessageReviewDecision.Action action) throws Exception {
        when(logs.recordReviewedViolation(message, MessageType.DM, text)).thenReturn(true);
        when(logs.recentCount(user)).thenReturn(3L);
        submit(service(context -> new MessageReviewDecision(action), Duration.ofSeconds(1)));
        drain(); outcome(action.name().toLowerCase(java.util.Locale.ROOT));
        if (action == MessageReviewDecision.Action.ALLOW) verifyNoInteractions(logs, sanctions);
        else {
            verify(logs, times(1)).recordReviewedViolation(message, MessageType.DM, text);
            verify(sanctions, times(1)).submit(user, MessageType.DM, target, text);
            assertThat(meters.get("moderation.violations").tag("source", "llm").tag("action", "violation").counter().count()).isEqualTo(1);
        }
        verify(contexts).loadMessage(user, MessageType.DM, target, message, text);
    }
    @Test void existingViolationDoesNotCountOrSubmitAgain() throws Exception {
        when(logs.recordReviewedViolation(message, MessageType.DM, text)).thenReturn(false);
        submit(service(context -> new MessageReviewDecision(MessageReviewDecision.Action.VIOLATION), Duration.ofSeconds(1)));
        drain(); verify(logs, never()).recentCount(any()); verifyNoInteractions(sanctions);
    }
    @Test void clearlyNormalAllowIsSkippedAfterCommitWithoutCallingReviewer() throws Exception {
        String normalQuestion = "오늘 경기 몇 시에 시작해?";
        when(contexts.loadMessage(user, MessageType.DM, target, message, normalQuestion))
            .thenReturn(new MessageReviewContext(normalQuestion, List.of()));
        MessageReviewer reviewer = mock(MessageReviewer.class);
        submit(service(reviewer, Duration.ofSeconds(1)), RuleAction.ALLOW, normalQuestion);
        drain(); outcome("skipped");
        verify(contexts).loadMessage(user, MessageType.DM, target, message, normalQuestion);
        verifyNoInteractions(reviewer, logs, sanctions);
        assertThat(meters.find("moderation.message.llm.calls").counter()).isNull();
    }
    @Test void belowThresholdDoesNotSubmitSanction() throws Exception {
        when(logs.recordReviewedViolation(message, MessageType.DM, text)).thenReturn(true);
        when(logs.recentCount(user)).thenReturn(2L);
        submit(service(context -> new MessageReviewDecision(MessageReviewDecision.Action.VIOLATION), Duration.ofSeconds(1)));
        drain(); verifyNoInteractions(sanctions);
    }
    @Test void modelErrorDoesNotBecomeAllowOrViolation() throws Exception {
        submit(service(context -> { throw new IllegalStateException("model error"); }, Duration.ofSeconds(1)));
        drain(); outcome("error"); verifyNoInteractions(logs, sanctions);
        assertThat(meters.find("moderation.message.reviews").tag("outcome", "allow").counter()).isNull();
    }
    @Test void transportTimeoutIsDistinctFromError() throws Exception {
        submit(service(context -> { throw new IllegalStateException(new java.net.SocketTimeoutException()); }, Duration.ofSeconds(1)));
        drain(); outcome("timeout"); verifyNoInteractions(logs, sanctions);
    }
    @Test void parsingFailureAndNullResponseNeverRecordViolation() throws Exception {
        submit(service(context -> { throw new InvalidMessageReviewResponseException(); }, Duration.ofSeconds(1)));
        drain(); outcome("invalid_response"); verifyNoInteractions(logs, sanctions);
    }
    @Test void nullDecisionIsInvalidResponse() throws Exception {
        submit(service(context -> null, Duration.ofSeconds(1)));
        drain(); outcome("invalid_response"); verifyNoInteractions(logs, sanctions);
    }
    @Test void fullQueueDoesNotRunContextOrModelOrRecord() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var bounded = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        MessageReviewer reviewer = mock(MessageReviewer.class);
        try {
            bounded.submit(() -> { started.countDown(); release.await(); return null; });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            bounded.submit(() -> { });
            var service = new MessageReviewService(contexts, reviewer, new MessageReviewRoutingFilter(), logs, sanctions, ModerationTestSettings.defaults(),
                MessageReviewTestSettings.defaults(), meters, bounded, scheduler);
            submit(service); outcome("queue_full");
            verifyNoInteractions(contexts, reviewer, logs, sanctions);
        } finally {
            release.countDown(); bounded.shutdownNow();
            assertThat(bounded.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void queueWaitConsumesDeadlineWithoutCallingModel() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var expiration = new AtomicReference<Runnable>();
        executor.submit(() -> { started.countDown(); release.await(); return null; });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        MessageReviewer reviewer = mock(MessageReviewer.class);
        try {
            submit(new MessageReviewService(contexts, reviewer, new MessageReviewRoutingFilter(), logs, sanctions, ModerationTestSettings.defaults(),
                MessageReviewTestSettings.withTimeout(Duration.ofSeconds(10)), meters, executor, manualTimeout(expiration)));
            expiration.get().run();
            outcome("timeout");
        } finally { release.countDown(); }
        drain(); verifyNoInteractions(contexts, reviewer, logs, sanctions);
    }
    @Test void contextTimeConsumesDeadlineWithoutCallingModel() throws Exception {
        var release = new CountDownLatch(1);
        var contextStarted = new CountDownLatch(1);
        var expiration = new AtomicReference<Runnable>();
        when(contexts.loadMessage(user, MessageType.DM, target, message, text)).thenAnswer(invocation -> {
            contextStarted.countDown();
            awaitIgnoringInterrupts(release); return new MessageReviewContext(text, List.of());
        });
        MessageReviewer reviewer = mock(MessageReviewer.class);
        try {
            submit(new MessageReviewService(contexts, reviewer, new MessageReviewRoutingFilter(), logs, sanctions, ModerationTestSettings.defaults(),
                MessageReviewTestSettings.withTimeout(Duration.ofSeconds(10)), meters, executor, manualTimeout(expiration)));
            assertThat(contextStarted.await(5, TimeUnit.SECONDS)).isTrue();
            expiration.get().run();
            outcome("timeout");
        } finally { release.countDown(); }
        drain(); verifyNoInteractions(reviewer, logs, sanctions);
    }
    @Test void timeoutDiscardsLateViolationEvenIfModelIgnoresCancellation() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var expiration = new AtomicReference<Runnable>();
        var service = new MessageReviewService(contexts, context -> {
            started.countDown(); awaitIgnoringInterrupts(release);
            return new MessageReviewDecision(MessageReviewDecision.Action.VIOLATION);
        }, new MessageReviewRoutingFilter(), logs, sanctions, ModerationTestSettings.defaults(),
            MessageReviewTestSettings.withTimeout(Duration.ofSeconds(10)), meters, executor, manualTimeout(expiration));
        try {
            submit(service);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            expiration.get().run();
            outcome("timeout");
        } finally { release.countDown(); }
        drain(); verifyNoInteractions(logs, sanctions);
        assertThat(meters.find("moderation.message.reviews").tag("outcome", "violation").counter()).isNull();
    }
    @Test void deadlineCheckRejectsLateResultEvenWhenTimerIsDelayed() throws Exception {
        ScheduledExecutorService delayed = mock(ScheduledExecutorService.class);
        var deadline = new AtomicLong();
        when(delayed.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            deadline.set(System.nanoTime() + invocation.getArgument(1, Long.class));
            return mock(ScheduledFuture.class);
        });
        var started = new CountDownLatch(1);
        var service = new MessageReviewService(contexts, context -> {
            started.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> System.nanoTime() - deadline.get() >= 0);
            return new MessageReviewDecision(MessageReviewDecision.Action.VIOLATION);
        }, new MessageReviewRoutingFilter(), logs, sanctions, ModerationTestSettings.defaults(), MessageReviewTestSettings.withTimeout(Duration.ofSeconds(5)),
            meters, executor, delayed);
        submit(service); assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        drain(); outcome("timeout"); verifyNoInteractions(logs, sanctions);
    }
    @Test void acceptedViolationCannotBeRecordedTwiceByLateTimer() throws Exception {
        ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
        var expiration = new AtomicReference<Runnable>();
        when(manual.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            expiration.set(invocation.getArgument(0)); return mock(ScheduledFuture.class);
        });
        when(logs.recordReviewedViolation(message, MessageType.DM, text)).thenReturn(true);
        var service = new MessageReviewService(contexts, context -> new MessageReviewDecision(MessageReviewDecision.Action.VIOLATION),
            new MessageReviewRoutingFilter(),
            logs, sanctions, ModerationTestSettings.defaults(), MessageReviewTestSettings.defaults(), meters, executor, manual);
        submit(service); drain(); expiration.get().run(); expiration.get().run();
        verify(logs, times(1)).recordReviewedViolation(message, MessageType.DM, text);
        outcome("violation");
        assertThat(meters.find("moderation.message.reviews").tag("outcome", "timeout").counter()).isNull();
    }
    @Test void persistenceFailureIsNotSuccessfulEvidence() throws Exception {
        when(logs.recordReviewedViolation(message, MessageType.DM, text)).thenThrow(new IllegalStateException("db"));
        submit(service(context -> new MessageReviewDecision(MessageReviewDecision.Action.VIOLATION), Duration.ofSeconds(1)));
        drain(); outcome("violation");
        assertThat(meters.get("moderation.message.review.persistence").tag("outcome", "error").counter().count()).isEqualTo(1);
        assertThat(meters.find("moderation.violations").counter()).isNull();
        verifyNoInteractions(sanctions);
    }
    private static ScheduledExecutorService manualTimeout(AtomicReference<Runnable> expiration) {
        ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
        when(manual.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            expiration.set(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        return manual;
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; } catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
