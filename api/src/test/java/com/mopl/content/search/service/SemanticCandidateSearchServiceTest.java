package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

@ExtendWith(MockitoExtension.class)
class SemanticCandidateSearchServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private AiAvailability aiAvailability;

    private SemanticCandidateSearchService semanticCandidateSearchService;

    @BeforeEach
    void setUp() {
        semanticCandidateSearchService = new SemanticCandidateSearchService(
                embeddingClient,
                elasticsearchOperations,
                contentRepository,
                aiAvailability
        );
    }

    @Test
    void returnsEmptyWithoutEmbeddingCallWhenAiIsUnavailable() {
        when(aiAvailability.isAvailable()).thenReturn(false);
        when(aiAvailability.unavailableReason())
                .thenReturn(AiAvailability.UnavailableReason.API_KEY_MISSING);

        assertThat(semanticCandidateSearchService.search(
                "query",
                null,
                10
        )).isEmpty();

        verify(embeddingClient, never()).embed(any());
        verifyNoInteractions(elasticsearchOperations);
    }
}
