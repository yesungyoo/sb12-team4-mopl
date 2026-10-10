package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import com.mopl.core.domain.content.entity.Content;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentSearchSyncKafkaConsumerTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentSearchIndexer contentSearchIndexer;

    @Mock
    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @Mock
    private Content content;

    private ContentSearchSyncKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer =
                new ContentSearchSyncKafkaConsumer(
                        contentRepository,
                        contentSearchIndexer,
                        contentEmbeddingIndexer
                );
    }

    @Test
    void indexesLatestContentWhenChangedEventIsConsumed() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        when(
                contentRepository
                        .findByIdAndDeletedAtIsNull(contentId)
        ).thenReturn(
                Optional.of(content)
        );

        consumer.consume(event);

        verify(contentRepository)
                .findByIdAndDeletedAtIsNull(contentId);

        InOrder indexingOrder =
                inOrder(
                        contentSearchIndexer,
                        contentEmbeddingIndexer
                );

        indexingOrder.verify(contentSearchIndexer)
                .index(content);

        indexingOrder.verify(contentEmbeddingIndexer)
                .index(content);

        verify(contentSearchIndexer, never())
                .delete(contentId);
    }

    @Test
    void deletesDocumentWhenDeletedEventIsConsumed() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        true
                );

        consumer.consume(event);

        verify(contentSearchIndexer)
                .delete(contentId);

        verify(contentRepository, never())
                .findByIdAndDeletedAtIsNull(contentId);

        verify(contentSearchIndexer, never())
                .index(content);

        verify(contentEmbeddingIndexer, never())
                .index(content);
    }

    @Test
    void deletesDocumentWhenContentNoLongerExists() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        when(
                contentRepository
                        .findByIdAndDeletedAtIsNull(contentId)
        ).thenReturn(
                Optional.empty()
        );

        consumer.consume(event);

        verify(contentRepository)
                .findByIdAndDeletedAtIsNull(contentId);

        verify(contentSearchIndexer)
                .delete(contentId);

        verify(contentSearchIndexer, never())
                .index(content);

        verify(contentEmbeddingIndexer, never())
                .index(content);
    }

    @Test
    void duplicateChangedEventsRemainIdempotent() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        when(
                contentRepository
                        .findByIdAndDeletedAtIsNull(contentId)
        ).thenReturn(
                Optional.of(content)
        );

        consumer.consume(event);
        consumer.consume(event);

        verify(contentRepository, times(2))
                .findByIdAndDeletedAtIsNull(contentId);

        verify(contentSearchIndexer, times(2))
                .index(content);

        verify(contentEmbeddingIndexer, times(2))
                .index(content);
    }

    @Test
    void duplicateDeletedEventsRemainIdempotent() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        true
                );

        consumer.consume(event);
        consumer.consume(event);

        verify(contentSearchIndexer, times(2))
                .delete(contentId);

        verify(contentRepository, never())
                .findByIdAndDeletedAtIsNull(contentId);

        verify(contentEmbeddingIndexer, never())
                .index(content);
    }

    @Test
    void propagatesFailureWhenIndexingFails() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        RuntimeException exception =
                new RuntimeException(
                        "Elasticsearch unavailable"
                );

        when(
                contentRepository
                        .findByIdAndDeletedAtIsNull(contentId)
        ).thenReturn(
                Optional.of(content)
        );

        org.mockito.Mockito.doThrow(exception)
                .when(contentSearchIndexer)
                .index(content);

        assertThatThrownBy(
                () -> consumer.consume(event)
        ).isSameAs(exception);

        verify(contentEmbeddingIndexer, never())
                .index(content);
    }

    @Test
    void propagatesFailureWhenDeletingFails() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        true
                );

        RuntimeException exception =
                new RuntimeException(
                        "Elasticsearch unavailable"
                );

        org.mockito.Mockito.doThrow(exception)
                .when(contentSearchIndexer)
                .delete(contentId);

        assertThatThrownBy(
                () -> consumer.consume(event)
        ).isSameAs(exception);

        verify(contentEmbeddingIndexer, never())
                .index(content);
    }
}
