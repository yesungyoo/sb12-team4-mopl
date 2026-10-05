package com.mopl.realtime.moderation.review;

import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.config.MessageReviewProperties;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.dto.MessageReviewDecision;
import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.exception.InvalidMessageReviewResponseException;
import com.mopl.realtime.moderation.service.ModerationContextService;
import com.mopl.realtime.moderation.service.ModerationLogService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MessageReviewService {
    private final ModerationContextService contexts;
    private final MessageReviewer reviewer;
    private final MessageReviewRoutingFilter routingFilter;
    private final ModerationLogService logs;
    private final ModerationReviewDispatcher sanctions;
    private final ModerationProperties moderationProperties;
    private final MessageReviewProperties properties;
    private final MeterRegistry meters;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public MessageReviewService(ModerationContextService contexts, MessageReviewer reviewer,
        MessageReviewRoutingFilter routingFilter, ModerationLogService logs,
        ModerationReviewDispatcher sanctions, ModerationProperties moderationProperties, MessageReviewProperties properties,
        MeterRegistry meters, @Qualifier("moderationMessageExecutor") ExecutorService executor,
        @Qualifier("moderationMessageScheduler") ScheduledExecutorService scheduler) {
        this.contexts = contexts; this.reviewer = reviewer; this.routingFilter = routingFilter;
        this.logs = logs; this.sanctions = sanctions;
        this.moderationProperties = moderationProperties; this.properties = properties;
        this.meters = meters; this.executor = executor; this.scheduler = scheduler;
    }

    public void reviewAfterCommit(UUID senderId, MessageType type, UUID targetId, UUID messageId, String text,
        RuleAction ruleAction) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            // 커밋 성공을 확인할 수 없는 호출에서는 사후 위반을 판정하지 않는다.
            meters.counter("moderation.message.review.submissions", "outcome", "no_transaction").increment();
            return;
        }
        var request = new ReviewRequest(senderId, type, targetId, messageId, text, ruleAction);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { submit(request); }
        });
    }

    private void submit(ReviewRequest request) {
        ReviewJob job = new ReviewJob(request);
        try {
            job.bindTimeout(scheduler.schedule(job::expire,
                Math.max(0, job.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS));
            executor.execute(job.task);
            if (job.task.isCancelled()) job.cancel();
        } catch (RejectedExecutionException exception) {
            job.finish(Outcome.QUEUE_FULL);
            job.cancel();
        } catch (RuntimeException exception) {
            job.finish(Outcome.ERROR);
            job.cancel();
        }
    }

    private void run(ReviewJob job) {
        var request = job.request;
        try {
            if (!job.canRun()) return;
            var context = contexts.loadMessage(request.senderId(), request.type(), request.targetId(),
                request.messageId(), request.text());
            if (!job.canRun()) return;
            if (!routingFilter.shouldRoute(request.ruleAction(), context)) {
                job.finish(Outcome.SKIPPED);
                return;
            }
            meters.counter("moderation.message.llm.calls").increment();
            job.observation.event("message_llm_started", "contextSize=" + context.recentConversation().size());
            Timer.Sample sample = Timer.start(meters);
            MessageReviewDecision decision;
            try { decision = reviewer.review(context); }
            finally { sample.stop(meters.timer("moderation.message.llm")); }
            if (decision == null || decision.action() == null) throw new InvalidMessageReviewResponseException();
            if (!job.accept(decision.action())) return;
            if (decision.action() == MessageReviewDecision.Action.VIOLATION) recordViolation(request);
        } catch (RuntimeException exception) {
            job.finish(isTimeout(exception) ? Outcome.TIMEOUT
                : exception instanceof InvalidMessageReviewResponseException ? Outcome.INVALID_RESPONSE : Outcome.ERROR);
        }
    }

    private void recordViolation(ReviewRequest request) {
        try {
            if (!logs.recordReviewedViolation(request.messageId(), request.type(), request.text())) return;
        } catch (RuntimeException exception) {
            meters.counter("moderation.message.review.persistence", "outcome", "error").increment();
            return;
        }
        meters.counter("moderation.violations", "source", "llm", "action", "violation").increment();
        try {
            if (logs.recentCount(request.senderId()) >= moderationProperties.violationThreshold()) {
                sanctions.submit(request.senderId(), request.type(), request.targetId(), request.text());
            }
        } catch (RuntimeException exception) {
            meters.counter("moderation.reviews", "outcome", "dispatch_error").increment();
        }
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private record ReviewRequest(UUID senderId, MessageType type, UUID targetId, UUID messageId, String text,
                                 RuleAction ruleAction) {
        @Override public String toString() { return "ReviewRequest[messageId=" + messageId + "]"; }
    }
    private enum Outcome { ALLOW, VIOLATION, SKIPPED, TIMEOUT, ERROR, INVALID_RESPONSE, QUEUE_FULL }

    private final class ReviewJob {
        private final ReviewRequest request;
        private final long deadlineNanos = System.nanoTime() + properties.timeout().toNanos();
        private final Timer.Sample sample = Timer.start(meters);
        private final ReviewObservation observation = new ReviewObservation(Instant.now().plus(properties.timeout()));
        private final FutureTask<Void> task;
        private ScheduledFuture<?> timeout;
        private boolean finished;

        private ReviewJob(ReviewRequest request) {
            this.request = request;
            task = new FutureTask<>(() -> { run(this); return null; });
            observation.event("message_review_started", "");
        }

        private synchronized void bindTimeout(ScheduledFuture<?> timeout) {
            this.timeout = timeout;
            if (finished) timeout.cancel(false);
        }

        private synchronized boolean canRun() {
            if (finished) return false;
            if (System.nanoTime() - deadlineNanos >= 0) { expire(); return false; }
            return true;
        }

        private synchronized boolean accept(MessageReviewDecision.Action action) {
            if (!canRun()) return false;
            return finish(action == MessageReviewDecision.Action.ALLOW ? Outcome.ALLOW : Outcome.VIOLATION);
        }

        private synchronized boolean finish(Outcome outcome) {
            if (finished) return false;
            finished = true;
            if (timeout != null) timeout.cancel(false);
            String label = outcome.name().toLowerCase(Locale.ROOT);
            meters.counter("moderation.message.reviews", "outcome", label).increment();
            sample.stop(meters.timer("moderation.message.review", "outcome", label));
            observation.event("message_review_finished", "outcome=" + label);
            return true;
        }

        private void expire() {
            if (finish(Outcome.TIMEOUT)) cancel();
        }

        private void cancel() {
            task.cancel(true);
            if (executor instanceof ThreadPoolExecutor pool) pool.remove(task);
        }
    }
}
