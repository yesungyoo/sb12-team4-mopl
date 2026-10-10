package com.mopl.content.search.kafka;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "spring.kafka",
        name = "bootstrap-servers"
)
public class ContentSearchSyncKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(
            UUID contentId,
            boolean deleted
    ) {
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        deleted
                );

        try {
            kafkaTemplate.send(
                    ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                    contentId.toString(),
                    event
            ).whenComplete((result, exception) -> {
                if (exception != null) {
                    log.error(
                            "Kafka 콘텐츠 동기화 이벤트 발행 실패. contentId={}, deleted={}",
                            contentId,
                            deleted,
                            exception
                    );
                }
            });
        } catch (RuntimeException e) {
            log.error(
                    "Kafka 콘텐츠 동기화 이벤트 발행 실패. contentId={}, deleted={}",
                    contentId,
                    deleted,
                    e
            );
        }
    }
}
