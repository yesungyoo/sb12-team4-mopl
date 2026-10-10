package com.mopl.batch.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

        assertThat(result).isSameAs(future);
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

        assertThat(result).isSameAs(future);
        assertThatThrownBy(result::join)
                .hasRootCauseMessage("Kafka 비동기 발행 실패");
    }
}
