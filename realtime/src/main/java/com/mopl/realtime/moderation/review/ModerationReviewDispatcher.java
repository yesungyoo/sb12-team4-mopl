package com.mopl.realtime.moderation.review;

import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore;
import com.mopl.realtime.moderation.service.ModerationContextService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ModerationReviewDispatcher {
    private final ChatRestrictionStore store;
    private final ModerationContextService contexts;
    private final ModerationReviewer reviewer;
    private final ModerationProperties properties;
    private final MeterRegistry meters;
    private final ExecutorService reviews;
    private final ExecutorService llm;

    public ModerationReviewDispatcher(ChatRestrictionStore store, ModerationContextService contexts,
        ModerationReviewer reviewer, ModerationProperties properties, MeterRegistry meters,
        @Qualifier("moderationReviewExecutor") ExecutorService reviews,
        @Qualifier("moderationLlmExecutor") ExecutorService llm) {
        this.store = store; this.contexts = contexts; this.reviewer = reviewer; this.properties = properties;
        this.meters = meters; this.reviews = reviews; this.llm = llm;
    }

    public void submit(UUID userId, MessageType type, UUID targetId, String text) {
        String token = store.claimReview(userId);
        if (token == null) {
            meters.counter("moderation.reviews", "outcome", "suppressed").increment();
            return;
        }
        Instant deadline = Instant.now().plus(properties.reviewTimeout());
        long submittedNanos = System.nanoTime();
        var observation = new ReviewObservation(deadline);
        observation.event("review_claimed", "");
        long scheduledNanos = System.nanoTime();
        try {
            reviews.execute(() -> run(userId, type, targetId, text, token, deadline, observation,
                scheduledNanos, submittedNanos));
        } catch (RejectedExecutionException exception) {
            observation.event("review_queue_rejected", "");
            meters.counter("moderation.reviews", "outcome", "queue_full").increment();
        }
    }

    private void run(UUID userId, MessageType type, UUID targetId, String text, String token, Instant deadline,
        ReviewObservation observation, long scheduledNanos, long submittedNanos) {
        double queueWaitMs = ReviewObservation.elapsedMs(scheduledNanos);
        Timer.Sample timer = Timer.start(meters);
        observation.event("review_executor_started", "queueWaitMs=" + queueWaitMs);
        String outcome = "error";
        String decisionOutcome = "error";
        long decisionFinishedNanos = System.nanoTime();
        var decisions = new SanctionDecisionInbox(deadline, observation);
        var tool = new SanctionTool(decisions, observation);
        Future<?> task = null;
        try {
            long llmScheduledNanos = System.nanoTime();
            task = llm.submit(() -> runWorker(userId, type, targetId, text, deadline, observation,
                llmScheduledNanos, decisions, tool));
            observation.event("decision_wait_started", "decisionState=" + decisions.state());
            var decision = decisions.await();
            decisionFinishedNanos = System.nanoTime();
            decisionOutcome = decision == null ? "no_decision" : "accepted";
            if (decision == null) {
                outcome = "no_decision";
            } else {
                // 결정의 유효성은 접수 시 확정하고, Redis에는 별도의 제한된 적용 기한을 전달한다.
                Instant applyDeadline = Instant.now().plus(properties.applyTimeout());
                observation.event("redis_apply_before", "level=" + decision.level() + " applyDeadline=" + applyDeadline);
                long applyStartedNanos = System.nanoTime();
                String applyOutcome = "error";
                boolean returned = false;
                boolean applied = false;
                try {
                    applied = store.apply(userId, token, decision.level(), decision.reason(), applyDeadline);
                    returned = true;
                    applyOutcome = applied ? "applied" : "rejected";
                    meters.counter("moderation.tool", "outcome", applyOutcome).increment();
                    if (applied) meters.counter("moderation.sanctions", "level", decision.level().name()).increment();
                    outcome = applied ? "completed" : "no_decision";
                } finally {
                    meters.timer("moderation.apply", "outcome", applyOutcome)
                        .record(System.nanoTime() - applyStartedNanos, TimeUnit.NANOSECONDS);
                    observation.event("redis_apply_after", "durationMs=" + ReviewObservation.elapsedMs(applyStartedNanos)
                        + " returned=" + returned + " result=" + (returned ? Boolean.toString(applied) : "unknown")
                        + " applyDeadline=" + applyDeadline + " toolMetricIncremented=" + returned
                        + " sanctionsMetricIncremented=" + (returned && applied));
                }
            }
        } catch (ExecutionException exception) {
            decisionFinishedNanos = System.nanoTime();
            if (exception.getCause() instanceof SanctionDecisionInbox.DecisionExpiredException) {
                outcome = "timeout";
                decisionOutcome = "timeout";
                observation.event("decision_timeout", taskState(task) + " decisionState=" + decisions.state());
            } else {
                observation.event("decision_error", "errorType=" + exception.getCause().getClass().getSimpleName());
                log.error("Moderation review failed. errorType={}", exception.getCause().getClass().getSimpleName());
            }
        } catch (RejectedExecutionException exception) {
            outcome = "queue_full";
            decisionOutcome = "queue_full";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            // 사용자 ID, 메시지 원문, 제재 사유 및 예외 상세 내용은 로그에 남기지 않는다.
            observation.event("review_error", "errorType=" + exception.getClass().getSimpleName());
            log.error("Moderation review failed. errorType={}", exception.getClass().getSimpleName());
        } finally {
            decisions.close();
            var accepted = decisions.acceptedDecision();
            if (accepted != null) {
                decisionOutcome = "accepted";
                decisionFinishedNanos = accepted.acceptedNanos();
                observation.event("worker_cleanup_after_decision", "phase=coordinator " + taskState(task));
                // 워커 정리가 정상 접수된 결정을 무효화하거나 처리를 중단하지 않도록 한다.
            } else {
                decisionFinishedNanos = System.nanoTime();
                if (task != null && !task.isDone()) {
                    observation.event("future_cancel_before", taskState(task));
                    boolean cancelAccepted = task.cancel(true);
                    observation.event("future_cancel_after", "cancelAccepted=" + cancelAccepted + " " + taskState(task));
                }
            }
            meters.timer("moderation.decision", "outcome", decisionOutcome)
                .record(Math.max(0, decisionFinishedNanos - submittedNanos), TimeUnit.NANOSECONDS);
            meters.counter("moderation.reviews", "outcome", outcome).increment();
            timer.stop(meters.timer("moderation.review", "outcome", outcome));
            observation.event("review_finished", "outcome=" + outcome + " decisionState=" + decisions.state()
                + " " + taskState(task));
        }
    }

    private void runWorker(UUID userId, MessageType type, UUID targetId, String text, Instant deadline,
        ReviewObservation observation, long scheduledNanos, SanctionDecisionInbox decisions, SanctionTool tool) {
        observation.event("llm_executor_started", "queueWaitMs=" + ReviewObservation.elapsedMs(scheduledNanos));
        Throwable failure = null;
        long workerStartedNanos = System.nanoTime();
        try {
            observation.event("context_load_started", "");
            long contextStarted = System.nanoTime();
            ModerationContextService.ReviewContext context;
            boolean contextReturned = false;
            try {
                context = contexts.load(userId, type, targetId, text);
                contextReturned = true;
            } finally {
                observation.event("context_load_finished", "durationMs=" + ReviewObservation.elapsedMs(contextStarted)
                    + " returned=" + contextReturned);
            }
            if (!Instant.now().isBefore(deadline)) {
                decisions.expireIfDue();
                observation.event("llm_skipped_expired", "");
                return;
            }
            var sample = Timer.start(meters);
            boolean reviewerReturned = false;
            observation.event("reviewer_entered", "");
            long reviewerStarted = System.nanoTime();
            try {
                reviewer.review(context, tool);
                reviewerReturned = true;
            } finally {
                sample.stop(meters.timer("moderation.llm"));
                observation.event("reviewer_finished", "durationMs=" + ReviewObservation.elapsedMs(reviewerStarted)
                    + " returned=" + reviewerReturned + " decisionState=" + decisions.state());
            }
        } catch (Throwable exception) {
            failure = exception;
            observation.event("worker_error", "errorType=" + exception.getClass().getSimpleName());
            throw exception;
        } finally {
            decisions.workerFinished(failure);
            observation.event("worker_finished", "durationMs=" + ReviewObservation.elapsedMs(workerStartedNanos)
                + " returned=" + (failure == null) + " decisionState=" + decisions.state());
            if (decisions.acceptedDecision() != null) {
                observation.event("worker_cleanup_after_decision", "phase=worker");
            }
        }
    }

    private static String taskState(Future<?> task) {
        return task == null ? "taskPresent=false"
            : "taskPresent=true taskDone=" + task.isDone() + " taskCancelled=" + task.isCancelled();
    }
}
