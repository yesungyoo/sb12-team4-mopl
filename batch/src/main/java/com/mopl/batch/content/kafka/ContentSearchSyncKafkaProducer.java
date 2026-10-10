package com.mopl.batch.content.kafka;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "spring.kafka",
        name = "bootstrap-servers"
)
public class ContentSearchSyncKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ContentSearchSyncKafkaProducer(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, Object>> publish(
            UUID contentId,
            boolean deleted
    ) {
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        deleted
                );

        try {
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(
                            ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                            contentId.toString(),
                            event
                    );

            if (future == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Kafka Producer가 전송 Future를 반환하지 않았습니다."
                        )
                );
            }

            return future;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(
                    exception
            );
        }
    }
}
