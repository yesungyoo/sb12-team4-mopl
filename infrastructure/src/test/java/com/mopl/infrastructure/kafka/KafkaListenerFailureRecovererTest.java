package com.mopl.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

@ExtendWith(OutputCaptureExtension.class)
class KafkaListenerFailureRecovererTest {

    private final KafkaListenerEndpointRegistry listenerEndpointRegistry =
            mock(KafkaListenerEndpointRegistry.class);
    private final KafkaListenerFailureRecoverer recoverer =
            new KafkaListenerFailureRecoverer(
                    new KafkaDeserializationFailureRecoverer(),
                    listenerEndpointRegistry
            );

    @Test
    void keepsDeserializationRecoveryPolicy() {
        ConsumerRecord<String, Object> record = record();
        Consumer<?, ?> consumer = mock(Consumer.class);
        DeserializationException exception = new DeserializationException(
                "역직렬화 실패",
                "invalid".getBytes(StandardCharsets.UTF_8),
                false,
                new IllegalArgumentException("잘못된 JSON")
        );

        assertThatCode(() -> recoverer.accept(
                record,
                consumer,
                exception
        )).doesNotThrowAnyException();

        verifyNoInteractions(listenerEndpointRegistry);
        verify(consumer, never()).pause(
                org.mockito.ArgumentMatchers.anyCollection()
        );
    }

    @Test
    void pausesFailedPartitionAndKeepsOffsetForListenerFailure(
            CapturedOutput output
    ) {
        ConsumerRecord<String, Object> record = record();
        Consumer<?, ?> consumer = mock(Consumer.class);
        RuntimeException failure = new RuntimeException(
                "Elasticsearch 연결 실패"
        );
        TopicPartition topicPartition = new TopicPartition(
                record.topic(),
                record.partition()
        );
        MessageListenerContainer listenerContainer =
                mock(MessageListenerContainer.class);
        when(listenerEndpointRegistry.getListenerContainers())
                .thenReturn(List.of(listenerContainer));
        when(listenerContainer.getAssignedPartitions())
                .thenReturn(List.of(topicPartition));

        assertThatThrownBy(() -> recoverer.accept(
                record,
                consumer,
                failure
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(failure);

        verify(listenerContainer).pausePartition(topicPartition);
        verify(consumer, never()).pause(
                org.mockito.ArgumentMatchers.anyCollection()
        );
        assertThat(output)
                .contains("재시도를 모두 소진")
                .contains("offsetPolicy=PAUSE_AND_KEEP_OFFSET")
                .contains("Elasticsearch 연결 실패");
    }

    private ConsumerRecord<String, Object> record() {
        return new ConsumerRecord<>(
                "content-search-sync",
                1,
                7L,
                "content-key",
                new Object()
        );
    }
}
