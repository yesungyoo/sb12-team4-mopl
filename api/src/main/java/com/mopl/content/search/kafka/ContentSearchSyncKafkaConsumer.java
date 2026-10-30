package com.mopl.content.search.kafka;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import com.mopl.infrastructure.ai.config.AiAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "spring.kafka",
        name = "bootstrap-servers"
)
public class ContentSearchSyncKafkaConsumer {

    public static final String LISTENER_ID = "content-search-sync-consumer";

    private final ContentRepository contentRepository;
    private final ContentSearchIndexer contentSearchIndexer;
    private final ContentEmbeddingKafkaProducer contentEmbeddingKafkaProducer;
    private final AiAvailability aiAvailability;

    @KafkaListener(
            id = LISTENER_ID,
            groupId = "${spring.kafka.consumer.group-id:mopl-group}",
            topics = ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
            containerFactory = "contentSearchSyncKafkaListenerContainerFactory",
            properties = "spring.json.remove.type.headers=false"
    )
    public void consume(ContentSearchSyncKafkaEvent event) {
        try {
            if (event.deleted()) {
                contentSearchIndexer.delete(event.contentId());
                return;
            }

            contentRepository.findByIdAndDeletedAtIsNull(event.contentId())
                    .ifPresentOrElse(
                            content -> {
                                contentSearchIndexer.index(content);
                                if (aiAvailability.isAvailable()) {
                                    contentEmbeddingKafkaProducer.publish(event.contentId());
                                }
                            },
                            () -> contentSearchIndexer.delete(event.contentId())
                    );
        } catch (RuntimeException e) {
            log.error(
                    "Kafka 콘텐츠 Elasticsearch 동기화 실패. contentId={}, deleted={}",
                    event.contentId(),
                    event.deleted(),
                    e
            );

            throw e;
        }
    }
}
