package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.config.AiAvailability;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentEmbeddingKafkaConsumerTest {

    @Mock private ContentRepository contentRepository;
    @Mock private ContentEmbeddingIndexer indexer;
    @Mock private AiAvailability aiAvailability;
    @Mock private Content content;

    private final UUID contentId = UUID.randomUUID();
    private ContentEmbeddingKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ContentEmbeddingKafkaConsumer(contentRepository, indexer, aiAvailability);
    }

    @Test
    void loadsLatestActiveContentAndPropagatesIndexingFailure() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.of(content));
        RuntimeException failure = new IllegalStateException("OpenAI unavailable");
        doThrow(failure).when(indexer).indexForKafka(content);

        assertThatThrownBy(() -> consumer.consume(new ContentEmbeddingKafkaEvent(contentId)))
                .isSameAs(failure);
        verify(indexer).indexForKafka(content);
    }

    @Test
    void skipsDeletedOrMissingContent() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.empty());

        consumer.consume(new ContentEmbeddingKafkaEvent(contentId));

        verifyNoInteractions(indexer);
    }

    @Test
    void rejectsConsumptionIfUnavailableListenerIsStartedManually() {
        when(aiAvailability.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> consumer.consume(new ContentEmbeddingKafkaEvent(contentId)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(contentRepository, indexer);
    }
}
