package com.mopl.batch.content.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ContentSearchSyncAfterCommitPublisher {

    private static final Logger log = LoggerFactory.getLogger(
            ContentSearchSyncAfterCommitPublisher.class
    );

    private final ObjectProvider<ContentSearchSyncKafkaProducer>
            producerProvider;
    private final long completionTimeoutMillis;

    public ContentSearchSyncAfterCommitPublisher(
            ObjectProvider<ContentSearchSyncKafkaProducer> producerProvider,
            @Value(
                    "${batch.content-search-sync.kafka-completion-timeout-ms:15000}"
            )
            long completionTimeoutMillis
    ) {
        if (completionTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "Kafka 전송 완료 대기 시간은 0보다 커야 합니다."
            );
        }

        this.producerProvider = producerProvider;
        this.completionTimeoutMillis = completionTimeoutMillis;
    }

    public void publishAfterCommit(
            List<ContentSearchSyncTarget> targets
    ) {
        if (targets == null || targets.isEmpty()) {
            return;
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager
                .isSynchronizationActive()) {
            log.error(
                    "활성 DB 트랜잭션이 없어 콘텐츠 동기화 이벤트를 "
                            + "커밋 이후로 예약할 수 없습니다. targetCount={}",
                    targets.size()
            );
            throw new IllegalStateException(
                    "콘텐츠 동기화 이벤트 예약에는 활성 DB 트랜잭션이 필요합니다."
            );
        }

        List<ContentSearchSyncTarget> immutableTargets =
                List.copyOf(targets);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publishSafely(immutableTargets);
                    }
                }
        );
    }

    private void publishSafely(
            List<ContentSearchSyncTarget> targets
    ) {
        try {
            ContentSearchSyncKafkaProducer producer =
                    producerProvider.getIfAvailable();

            if (producer == null) {
                log.error(
                        "spring.kafka.bootstrap-servers 설정이 없어 DB 커밋 후 "
                                + "콘텐츠 동기화 이벤트를 발행하지 못했습니다. "
                                + "targetCount={}",
                        targets.size()
                );
                return;
            }

            List<PendingSend> pendingSends = new ArrayList<>(
                    targets.size()
            );

            for (ContentSearchSyncTarget target : targets) {
                CompletableFuture<SendResult<String, Object>> future;
                try {
                    future = producer.publish(
                            target.contentId(),
                            target.deleted()
                    );
                } catch (RuntimeException exception) {
                    future = CompletableFuture.failedFuture(
                            exception
                    );
                }

                if (future == null) {
                    future = CompletableFuture.failedFuture(
                            new IllegalStateException(
                                    "Kafka Producer가 전송 Future를 반환하지 않았습니다."
                            )
                    );
                }

                pendingSends.add(new PendingSend(target, future));
            }

            awaitCompletion(pendingSends);
        } catch (RuntimeException exception) {
            // DB는 이미 커밋되었으므로 Kafka 오류를 Batch 실패로 전파하지 않는다.
            log.error(
                    "DB 커밋 후 Kafka 콘텐츠 동기화 이벤트 발행 처리 중 "
                            + "예상하지 못한 오류가 발생했습니다. targetCount={}",
                    targets.size(),
                    exception
            );
        }
    }

    private void awaitCompletion(List<PendingSend> pendingSends) {
        CompletableFuture<?>[] futures =
                new CompletableFuture<?>[pendingSends.size()];
        for (int index = 0; index < pendingSends.size(); index++) {
            futures[index] = pendingSends.get(index).future();
        }

        CompletableFuture<Void> allSends = CompletableFuture.allOf(futures);
        CompletionWaitStatus waitStatus = CompletionWaitStatus.COMPLETED;

        try {
            allSends.get(
                    completionTimeoutMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (ExecutionException exception) {
            // 개별 Future에서 콘텐츠 UUID와 함께 실패 원인을 기록한다.
        } catch (TimeoutException exception) {
            waitStatus = CompletionWaitStatus.TIMED_OUT;
        } catch (InterruptedException exception) {
            waitStatus = CompletionWaitStatus.INTERRUPTED;
            Thread.currentThread().interrupt();
        }

        for (PendingSend pendingSend : pendingSends) {
            logCompletionResult(pendingSend, waitStatus);
        }

        if (waitStatus != CompletionWaitStatus.COMPLETED) {
            cancelIncompleteSends(pendingSends);
        }

        log.info(
                "DB 커밋 후 Kafka 콘텐츠 동기화 이벤트 전송 결과 확인 종료. "
                        + "targetCount={}, timeoutMillis={}, waitStatus={}",
                pendingSends.size(),
                completionTimeoutMillis,
                waitStatus
        );
    }

    private void cancelIncompleteSends(List<PendingSend> pendingSends) {
        for (PendingSend pendingSend : pendingSends) {
            CompletableFuture<SendResult<String, Object>> future =
                    pendingSend.future();

            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private void logCompletionResult(
            PendingSend pendingSend,
            CompletionWaitStatus waitStatus
    ) {
        ContentSearchSyncTarget target = pendingSend.target();
        CompletableFuture<SendResult<String, Object>> future =
                pendingSend.future();

        if (!future.isDone()) {
            if (waitStatus == CompletionWaitStatus.INTERRUPTED) {
                log.error(
                        "Kafka 콘텐츠 동기화 이벤트 전송 결과 미확인. "
                                + "contentId={}, deleted={}, "
                                + "cause={}",
                        target.contentId(),
                        target.deleted(),
                        "대기 스레드 인터럽트"
                );
                return;
            }

            log.error(
                    "Kafka 콘텐츠 동기화 이벤트 전송 결과 미확인. "
                            + "contentId={}, deleted={}, "
                            + "timeoutMillis={}, cause={}",
                    target.contentId(),
                    target.deleted(),
                    completionTimeoutMillis,
                    "제한 시간 내 브로커 ACK 미확인"
            );
            return;
        }

        try {
            future.join();
            log.info(
                    "Kafka 콘텐츠 동기화 이벤트 브로커 ACK 확인 성공. "
                            + "contentId={}, deleted={}",
                    target.contentId(),
                    target.deleted()
            );
        } catch (RuntimeException exception) {
            Throwable cause = unwrap(exception);
            log.error(
                    "Kafka 콘텐츠 동기화 이벤트 전송 실패. "
                            + "contentId={}, deleted={}, cause={}",
                    target.contentId(),
                    target.deleted(),
                    cause.getMessage(),
                    cause
            );
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record PendingSend(
            ContentSearchSyncTarget target,
            CompletableFuture<SendResult<String, Object>> future
    ) {
    }

    private enum CompletionWaitStatus {
        COMPLETED("완료"),
        TIMED_OUT("시간 초과"),
        INTERRUPTED("중단");

        private final String description;

        CompletionWaitStatus(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
