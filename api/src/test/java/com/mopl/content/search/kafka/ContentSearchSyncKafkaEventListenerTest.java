package com.mopl.content.search.kafka;

import static org.mockito.Mockito.verify;

import com.mopl.content.search.event.ContentSearchSyncEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentSearchSyncKafkaEventListenerTest {

    @Mock
    private ContentSearchSyncKafkaProducer contentSearchSyncKafkaProducer;

    private ContentSearchSyncKafkaEventListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new ContentSearchSyncKafkaEventListener(
                        contentSearchSyncKafkaProducer
                );
    }

    @Test
    void publishesChangedEventToKafkaProducer() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncEvent event =
                new ContentSearchSyncEvent(
                        contentId,
                        false
                );

        listener.handle(event);

        verify(contentSearchSyncKafkaProducer)
                .publish(
                        contentId,
                        false
                );
    }

    @Test
    void publishesDeletedEventToKafkaProducer() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncEvent event =
                new ContentSearchSyncEvent(
                        contentId,
                        true
                );

        listener.handle(event);

        verify(contentSearchSyncKafkaProducer)
                .publish(
                        contentId,
                        true
                );
    }
}
