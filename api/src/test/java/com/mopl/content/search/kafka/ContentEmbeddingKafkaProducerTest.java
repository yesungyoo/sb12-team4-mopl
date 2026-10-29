package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class ContentEmbeddingKafkaProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final UUID contentId = UUID.randomUUID();
    private ContentEmbeddingKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ContentEmbeddingKafkaProducer(kafkaTemplate, 20);
    }

    @Test
    void waitsForAcknowledgementAndUsesContentIdKey() {
        when(kafkaTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(), new ContentEmbeddingKafkaEvent(contentId)))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> producer.publish(contentId)).doesNotThrowAnyException();
        verify(kafkaTemplate).send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(), new ContentEmbeddingKafkaEvent(contentId));
    }

    @Test
    void propagatesSynchronousSendFailure() {
        RuntimeException failure = new IllegalStateException("metadata unavailable");
        when(kafkaTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(), new ContentEmbeddingKafkaEvent(contentId))).thenThrow(failure);

        assertThatThrownBy(() -> producer.publish(contentId)).isSameAs(failure);
    }

    @Test
    void preservesAsynchronousFailureCause() {
        RuntimeException failure = new IllegalStateException("broker unavailable");
        when(kafkaTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(), new ContentEmbeddingKafkaEvent(contentId)))
                .thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> producer.publish(contentId))
                .isInstanceOf(IllegalStateException.class).hasCause(failure);
    }

    @Test
    void timesOutWithoutCancellingPossibleLateDelivery() {
        CompletableFuture<SendResult<String, Object>> pending = new CompletableFuture<>();
        when(kafkaTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(), new ContentEmbeddingKafkaEvent(contentId))).thenReturn(pending);

        assertThatThrownBy(() -> producer.publish(contentId))
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(pending.isCancelled()).isFalse();
        assertThat(pending.complete(null)).isTrue();
    }

    @Test
    void restoresInterruptStatus() {
        when(kafkaTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                contentId.toString(), new ContentEmbeddingKafkaEvent(contentId)))
                .thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> producer.publish(contentId))
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
