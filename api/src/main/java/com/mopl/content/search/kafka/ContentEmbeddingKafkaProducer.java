package com.mopl.content.search.kafka;

import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ContentEmbeddingKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final long ackTimeoutMs;

    public ContentEmbeddingKafkaProducer(
            @Qualifier("contentEmbeddingKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${mopl.kafka.embedding.producer.ack-timeout-ms:15000}") long ackTimeoutMs
    ) {
        if (ackTimeoutMs <= 0) {
            throw new IllegalArgumentException("Embedding Kafka ACK 대기 시간은 양수여야 합니다.");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public void publish(UUID contentId) {
        try {
            kafkaTemplate.send(
                    ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                    contentId.toString(),
                    new ContentEmbeddingKafkaEvent(contentId)
            ).get(ackTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding Kafka 발행 대기가 중단되었습니다.", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Embedding Kafka 발행에 실패했습니다.", exception.getCause());
        } catch (TimeoutException exception) {
            // Timeout 뒤 늦은 ACK가 도착할 수 있다. 원본 재처리 시 중복 발행을 허용한다.
            throw new IllegalStateException("Embedding Kafka 발행 ACK 대기 시간이 초과되었습니다.", exception);
        }
    }
}
