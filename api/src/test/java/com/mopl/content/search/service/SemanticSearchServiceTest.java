package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.common.exception.content.SemanticSearchUnavailableException;
import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.repository.ContentRepository;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.exception.AiClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

@ExtendWith(MockitoExtension.class)
class SemanticSearchServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private AiAvailability aiAvailability;

    private SemanticSearchService semanticSearchService;

    @BeforeEach
    void setUp() {
        semanticSearchService = new SemanticSearchService(
                embeddingClient,
                elasticsearchOperations,
                contentRepository,
                aiAvailability
        );
    }

    @Test
    void returnsEmptyResultWithoutEmbeddingCallWhenAiIsUnavailable() {
        when(aiAvailability.isAvailable()).thenReturn(false);
        when(aiAvailability.unavailableReason())
                .thenReturn(AiAvailability.UnavailableReason.AI_DISABLED);

        ContentListResponse response = semanticSearchService.search(
                "query",
                PageRequest.of(0, 20)
        );

        assertThat(response.contents()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verify(embeddingClient, never()).embed(any());
        verifyNoInteractions(elasticsearchOperations);
    }

    @Test
    void throwsServiceUnavailableWhenEmbeddingApiFails() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(embeddingClient.embed(any()))
                .thenThrow(new AiClientException("embedding failure"));

        assertThatThrownBy(() -> semanticSearchService.search(
                "query",
                PageRequest.of(0, 20)
        )).isInstanceOf(SemanticSearchUnavailableException.class);

        verifyNoInteractions(elasticsearchOperations);
    }
}
