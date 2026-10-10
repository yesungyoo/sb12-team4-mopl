package com.mopl.batch.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class ContentSearchSyncKafkaProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ContentSearchSyncKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ContentSearchSyncKafkaProducer(kafkaTemplate);
    }

    @AfterEach
    void tearDown() {
        producer.shutdownSendExecutor();
    }

    @Test
    void publishesCommonEventWithContentUuidAsKey() {
        UUID contentId = UUID.randomUUID();
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(contentId, true);
        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(null);

        when(kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                contentId.toString(),
                event
        )).thenReturn(future);

        CompletableFuture<SendResult<String, Object>> result =
                producer.publish(contentId, true);

        assertThat(result.join()).isNull();
        verify(kafkaTemplate).send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                contentId.toString(),
                event
        );
    }

    @Test
    void returnsFailedFutureForImmediateSendFailure() {
        UUID contentId = UUID.randomUUID();

        when(kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                contentId.toString(),
                new ContentSearchSyncKafkaEvent(contentId, false)
        )).thenThrow(new RuntimeException("Kafka 연결 실패"));

        CompletableFuture<SendResult<String, Object>> result =
                producer.publish(contentId, false);

        assertThatThrownBy(result::join)
                .hasRootCauseMessage("Kafka 연결 실패");
    }

    @Test
    void returnsKafkaFutureForAsynchronousFailure() {
        UUID contentId = UUID.randomUUID();
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(contentId, false);
        CompletableFuture<SendResult<String, Object>> future =
                new CompletableFuture<>();
        future.completeExceptionally(
                new RuntimeException("Kafka 비동기 발행 실패")
        );

        when(kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                contentId.toString(),
                event
        )).thenReturn(future);

        CompletableFuture<SendResult<String, Object>> result =
                producer.publish(contentId, false);

        assertThatThrownBy(result::join)
                .hasRootCauseMessage("Kafka 비동기 발행 실패");
    }

    @Test
    void metadataWaitDoesNotAccumulateOnCallingThread() throws Exception {
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();
        CountDownLatch enteredSend = new CountDownLatch(2);
        CountDownLatch releaseSend = new CountDownLatch(1);

        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            enteredSend.countDown();
            releaseSend.await(1, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<SendResult<String, Object>> first =
                producer.publish(firstContentId, false);
        CompletableFuture<SendResult<String, Object>> second =
                producer.publish(secondContentId, false);

        assertThat(enteredSend.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();

        releaseSend.countDown();

        CompletableFuture.allOf(first, second).join();
    }

    @Test
    void cancellationInterruptsBlockedMetadataWait() throws Exception {
        UUID contentId = UUID.randomUUID();
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch interruptedSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);

        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            enteredSend.countDown();
            try {
                releaseSend.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interruptedSend.countDown();
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Kafka 메타데이터 대기 중단",
                        exception
                );
            }
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<SendResult<String, Object>> result =
                producer.publish(contentId, false);

        assertThat(enteredSend.await(500, TimeUnit.MILLISECONDS)).isTrue();

        try {
            assertThat(result.cancel(true)).isTrue();
            assertThat(interruptedSend.await(500, TimeUnit.MILLISECONDS))
                    .isTrue();
        } finally {
            releaseSend.countDown();
        }
    }
}
