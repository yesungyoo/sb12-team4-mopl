package com.mopl.content.search.kafka;

import com.mopl.content.search.event.ContentSearchSyncEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "spring.kafka",
        name = "bootstrap-servers"
)
public class ContentSearchSyncKafkaEventListener {

    private final ContentSearchSyncKafkaProducer contentSearchSyncKafkaProducer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ContentSearchSyncEvent event) {
        contentSearchSyncKafkaProducer.publish(
                event.contentId(),
                event.deleted()
        );
    }
}
