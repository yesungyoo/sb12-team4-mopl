package com.mopl.realtime.moderation.review;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 로그 연결에만 사용하며, 기한 판정이나 메트릭 라벨에는 사용하지 않는다. */
final class ReviewObservation {
    private static final Logger log = LoggerFactory.getLogger(ReviewObservation.class);
    private final String reviewId = UUID.randomUUID().toString();
    private final Instant deadline;
    private final long startedNanos = System.nanoTime();

    ReviewObservation(Instant deadline) { this.deadline = deadline; }

    static double elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    void event(String stage, String details) {
        boolean diagnostic = diagnostic(stage, details);
        if (!diagnostic && !log.isDebugEnabled()) return;
        // 관측 오류가 모더레이션 처리 결과에 영향을 주지 않도록 한다.
        try {
            Instant at = Instant.now();
            double elapsed = elapsedMs(startedNanos);
            double remaining = Duration.between(at, deadline).toNanos() / 1_000_000.0;
            if (diagnostic) {
                log.info("Moderation observation reviewId={} stage={} at={} deadline={} elapsedMs={} remainingMs={} {}",
                    reviewId, stage, at, deadline, elapsed, remaining, details);
            } else {
                log.debug("Moderation observation reviewId={} stage={} at={} deadline={} elapsedMs={} remainingMs={} {}",
                    reviewId, stage, at, deadline, elapsed, remaining, details);
            }
        } catch (RuntimeException ignored) {
            // 로그 기록 실패는 무시하며, 메시지 내용이나 사용자 식별자는 포함하지 않는다.
        }
    }

    private static boolean diagnostic(String stage, String details) {
        return switch (stage) {
            case "review_queue_rejected", "decision_timeout", "decision_expired", "decision_error",
                "review_error", "worker_error", "llm_skipped_expired" -> true;
            case "message_review_finished", "review_finished" ->
                details.startsWith("outcome=timeout") || details.startsWith("outcome=error")
                    || details.startsWith("outcome=invalid_response") || details.startsWith("outcome=queue_full")
                    || details.startsWith("outcome=no_decision");
            default -> false;
        };
    }
}
