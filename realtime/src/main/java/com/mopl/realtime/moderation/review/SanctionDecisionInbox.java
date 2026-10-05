package com.mopl.realtime.moderation.review;

import com.mopl.realtime.moderation.dto.SanctionLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 심사마다 서버가 하나의 결정을 관리하며, 접수와 만료를 동일한 모니터로 동기화한다. */
final class SanctionDecisionInbox {
    enum State { WAITING, ACCEPTED, EXPIRED }
    record Decision(SanctionLevel level, String reason, Instant acceptedAt, long acceptedNanos) {
        @Override public String toString() { return "Decision[level=" + level + ", acceptedAt=" + acceptedAt + "]"; }
    }
    static final class DecisionExpiredException extends RuntimeException {}

    private final Instant deadline;
    private final Clock clock;
    private final ReviewObservation observation;
    private final CompletableFuture<Decision> result = new CompletableFuture<>();
    private State state = State.WAITING;
    private Decision decision;

    SanctionDecisionInbox(Instant deadline, ReviewObservation observation) {
        this(deadline, observation, Clock.systemUTC());
    }
    SanctionDecisionInbox(Instant deadline, ReviewObservation observation, Clock clock) {
        this.deadline = deadline; this.observation = observation; this.clock = clock;
    }

    synchronized boolean submit(SanctionLevel level, String reason) {
        if (level == null || reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("Invalid sanction decision");
        }
        if (state != State.WAITING) return false;
        Instant now = clock.instant();
        if (!now.isBefore(deadline)) {
            expire();
            return false;
        }
        decision = new Decision(level, reason, now, System.nanoTime());
        state = State.ACCEPTED;
        observation.event("decision_accepted", "level=" + level + " acceptedAt=" + now);
        result.complete(decision);
        return true;
    }

    synchronized void expireIfDue() {
        if (state == State.WAITING && !clock.instant().isBefore(deadline)) expire();
    }
    private void expire() {
        state = State.EXPIRED;
        observation.event("decision_expired", "");
        result.completeExceptionally(new DecisionExpiredException());
    }

    // 워커 오류나 반환은 미접수 심사를 종료할 수 있지만, 이미 접수된 결정은 폐기하지 않는다.
    synchronized void workerFinished(Throwable failure) {
        if (state != State.WAITING) return;
        if (!clock.instant().isBefore(deadline)) {
            expire();
            return;
        }
        state = State.EXPIRED;
        if (failure == null) result.complete(null);
        else result.completeExceptionally(failure);
    }
    synchronized void close() {
        if (state == State.WAITING) {
            state = State.EXPIRED;
            result.complete(null);
        }
    }

    Decision await() throws InterruptedException, ExecutionException {
        while (!result.isDone()) {
            long remaining = Duration.between(clock.instant(), deadline).toNanos();
            if (remaining <= 0) {
                expireIfDue();
            } else {
                try { return result.get(remaining, TimeUnit.NANOSECONDS); }
                catch (TimeoutException ignored) { expireIfDue(); }
            }
        }
        // 만료 처리와 결정 제출을 동기화하여, 기한 전에 접수된 결정은 항상 보존한다.
        return result.get();
    }
    synchronized Decision acceptedDecision() { return decision; }
    synchronized State state() { return state; }
}
