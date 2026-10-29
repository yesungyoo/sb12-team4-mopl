package com.mopl.content.search.kafka;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.infrastructure.ai.config.AiAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ContentEmbeddingKafkaConsumer {

    public static final String LISTENER_ID = "content-embedding-consumer";

    private final ContentRepository contentRepository;
    private final ContentEmbeddingIndexer contentEmbeddingIndexer;
    private final AiAvailability aiAvailability;

    @KafkaListener(
            id = LISTENER_ID,
            topics = ContentSearchKafkaTopics.CONTENT_EMBEDDING,
            groupId = "${mopl.kafka.embedding.group-id:mopl-content-embedding-group}",
            containerFactory = "contentEmbeddingKafkaListenerContainerFactory",
            concurrency = "${mopl.kafka.embedding.concurrency:1}",
            autoStartup = "#{@aiAvailability.isAvailable()}",
            properties = {
                    "auto.offset.reset=earliest",
                    "max.poll.records=${mopl.kafka.embedding.max-poll-records:1}",
                    "max.poll.interval.ms=${mopl.kafka.embedding.max-poll-interval-ms:300000}"
            }
    )
    public void consume(ContentEmbeddingKafkaEvent event) {
        // 비활성 Listener를 명시적으로 시작해도 backlog를 no-op ACK하지 않는다.
        if (!aiAvailability.isAvailable()) {
            throw new IllegalStateException("AI 사용 불가 상태에서는 Embedding을 소비할 수 없습니다.");
        }

        contentRepository.findByIdAndDeletedAtIsNull(event.contentId())
                .ifPresent(contentEmbeddingIndexer::indexForKafka);
    }
}
