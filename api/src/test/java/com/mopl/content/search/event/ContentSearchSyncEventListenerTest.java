package com.mopl.content.search.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

import com.mopl.content.search.service.ContentSearchIndexer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentSearchSyncEventListenerTest {

    @Mock
    private ContentSearchIndexer contentSearchIndexer;

    private ContentSearchSyncEventListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new ContentSearchSyncEventListener(
                        contentSearchIndexer
                );
    }

    @Test
    void updatesStatisticsWhenStatisticsEventIsPublished() {
        UUID contentId = UUID.randomUUID();

        ContentSearchStatisticsSyncEvent event =
                new ContentSearchStatisticsSyncEvent(
                        contentId
                );

        listener.handle(event);

        verify(contentSearchIndexer)
                .updateStatistics(contentId);
    }

    @Test
    void doesNotPropagateExceptionWhenStatisticsSyncFails() {
        UUID contentId = UUID.randomUUID();

        ContentSearchStatisticsSyncEvent event =
                new ContentSearchStatisticsSyncEvent(
                        contentId
                );

        org.mockito.Mockito.doThrow(
                        new RuntimeException("Elasticsearch unavailable")
                ).when(contentSearchIndexer)
                .updateStatistics(contentId);

        assertThatCode(
                () -> listener.handle(event)
        ).doesNotThrowAnyException();

        verify(contentSearchIndexer)
                .updateStatistics(contentId);
    }
}
