package com.mopl.batch.content.kafka;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "spring.kafka",
        name = "bootstrap-servers"
)
public class ContentSearchSyncKafkaProducer {

    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ExecutorService sendExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public ContentSearchSyncKafkaProducer(
            @Qualifier("batchContentSearchKafkaTemplate")
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, Object>> publish(
            UUID contentId,
            boolean deleted
    ) {
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        deleted
                );

        CompletableFuture<SendResult<String, Object>> completion =
                new CompletableFuture<>();
        AtomicReference<Future<?>> submittedTask = new AtomicReference<>();
        AtomicReference<CompletableFuture<SendResult<String, Object>>>
                kafkaSendFuture = new AtomicReference<>();

        completion.whenComplete((result, exception) -> {
            if (!completion.isCancelled()) {
                return;
            }

            Future<?> task = submittedTask.get();
            if (task != null) {
                task.cancel(true);
            }

            CompletableFuture<SendResult<String, Object>> sendFuture =
                    kafkaSendFuture.get();
            if (sendFuture != null) {
                sendFuture.cancel(true);
            }
        });

        try {
            Future<?> task = sendExecutor.submit(() -> {
                try {
                    CompletableFuture<SendResult<String, Object>> sendFuture =
                            send(contentId, event);
                    kafkaSendFuture.set(sendFuture);

                    if (completion.isCancelled()) {
                        sendFuture.cancel(true);
                        return;
                    }

                    sendFuture.whenComplete((result, exception) -> {
                        if (exception == null) {
                            completion.complete(result);
                        } else {
                            completion.completeExceptionally(exception);
                        }
                    });
                } catch (Exception exception) {
                    completion.completeExceptionally(exception);
                }
            });
            submittedTask.set(task);

            if (completion.isCancelled()) {
                task.cancel(true);
            }
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }

        return completion;
    }

    private CompletableFuture<SendResult<String, Object>> send(
            UUID contentId,
            ContentSearchSyncKafkaEvent event
    ) {
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                );

        if (future == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Kafka Producer가 전송 Future를 반환하지 않았습니다."
                    )
            );
        }

        return future;
    }

    @PreDestroy
    void shutdownSendExecutor() {
        // 종료 시 메타데이터 조회로 대기 중인 발행 작업을 계속 유지하지 않는다.
        sendExecutor.shutdownNow();

        try {
            sendExecutor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
