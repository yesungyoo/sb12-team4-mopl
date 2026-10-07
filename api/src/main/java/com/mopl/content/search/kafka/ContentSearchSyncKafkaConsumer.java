package com.mopl.content.search.kafka;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.kafka.event.ContentSearchSyncKafkaEvent;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
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

    private final ContentRepository contentRepository;
    private final ContentSearchIndexer contentSearchIndexer;
    private final ContentEmbeddingIndexer contentEmbeddingIndexer;

    @KafkaListener(
            topics = ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
            containerFactory = "kafkaListenerContainerFactory"
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
                                contentEmbeddingIndexer.index(content);
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
