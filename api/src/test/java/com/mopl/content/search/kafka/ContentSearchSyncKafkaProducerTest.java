package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
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
        producer =
                new ContentSearchSyncKafkaProducer(
                        kafkaTemplate
                );
    }

    @Test
    void publishesChangedEventWithContentIdAsKey() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(null);

        when(
                kafkaTemplate.send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                )
        ).thenReturn(future);

        producer.publish(
                contentId,
                false
        );

        verify(kafkaTemplate)
                .send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                );
    }

    @Test
    void publishesDeletedEventWithContentIdAsKey() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        true
                );

        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(null);

        when(
                kafkaTemplate.send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                )
        ).thenReturn(future);

        producer.publish(
                contentId,
                true
        );

        verify(kafkaTemplate)
                .send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                );
    }

    @Test
    void doesNotPropagateSynchronousKafkaSendFailure() {
        UUID contentId = UUID.randomUUID();

        when(
                kafkaTemplate.send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        new ContentSearchSyncKafkaEvent(
                                contentId,
                                false
                        )
                )
        ).thenThrow(
                new RuntimeException(
                        "Kafka unavailable"
                )
        );

        assertThatCode(
                () -> producer.publish(
                        contentId,
                        false
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void doesNotPropagateAsynchronousKafkaSendFailure() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        CompletableFuture<SendResult<String, Object>> future =
                new CompletableFuture<>();

        future.completeExceptionally(
                new RuntimeException(
                        "Kafka async failure"
                )
        );

        when(
                kafkaTemplate.send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                )
        ).thenReturn(future);

        assertThatCode(
                () -> producer.publish(
                        contentId,
                        false
                )
        ).doesNotThrowAnyException();

        verify(kafkaTemplate)
                .send(
                        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                        contentId.toString(),
                        event
                );
    }
}
