package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.*;

import com.mopl.realtime.moderation.dto.SanctionLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SanctionDecisionInboxTest {
    private final Instant deadline = Instant.parse("2026-10-02T00:00:05Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(deadline.minusSeconds(1));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private SanctionDecisionInbox inbox() {
        return new SanctionDecisionInbox(deadline, new ReviewObservation(deadline), clock);
    }
    @Test void acceptedDecisionSurvivesExpiryCloseAndWorkerFailure() throws Exception {
        var inbox = inbox();
        assertThat(inbox.submit(SanctionLevel.TEMPORARY_SHORT, "사유")).isTrue();
        now.set(deadline.plusSeconds(1));
        inbox.expireIfDue();
        inbox.close();
        inbox.workerFinished(new IllegalStateException("cleanup failed"));
        assertThat(inbox.await().level()).isEqualTo(SanctionLevel.TEMPORARY_SHORT);
        assertThat(inbox.state()).isEqualTo(SanctionDecisionInbox.State.ACCEPTED);
    }
    @Test void exactDeadlineIsExpired() {
        var inbox = inbox();
        now.set(deadline);
        assertThat(inbox.submit(SanctionLevel.TEMPORARY_LONG, "사유")).isFalse();
        assertThatThrownBy(inbox::await).isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(SanctionDecisionInbox.DecisionExpiredException.class);
    }
    @Test void concurrentSubmissionsOnlyAcceptOne() throws Exception {
        var inbox = inbox();
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 24; i++) pool.submit(() -> {
                start.await();
                if (inbox.submit(SanctionLevel.NONE, "사유")) accepted.incrementAndGet();
                return null;
            });
            start.countDown();
        }
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(inbox.await().level()).isEqualTo(SanctionLevel.NONE);
    }
    @Test void simultaneousExpiryAndSubmissionCannotLoseAcceptedDecision() throws Exception {
        for (int i = 0; i < 30; i++) {
            now.set(deadline.minusSeconds(1));
            var inbox = inbox();
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var submit = pool.submit(() -> { start.await(); return inbox.submit(SanctionLevel.NONE, "사유"); });
                var expire = pool.submit(() -> { start.await(); now.set(deadline); inbox.expireIfDue(); return null; });
                start.countDown();
                boolean accepted = submit.get(1, TimeUnit.SECONDS);
                expire.get(1, TimeUnit.SECONDS);
                if (accepted) {
                    assertThat(inbox.await().level()).isEqualTo(SanctionLevel.NONE);
                    assertThat(inbox.state()).isEqualTo(SanctionDecisionInbox.State.ACCEPTED);
                } else {
                    assertThat(inbox.state()).isEqualTo(SanctionDecisionInbox.State.EXPIRED);
                    assertThat(inbox.acceptedDecision()).isNull();
                }
            }
        }
    }
}
