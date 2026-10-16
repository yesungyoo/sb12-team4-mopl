package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.common.exception.content.SemanticSearchUnavailableException;
import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;

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

    @Test
    void excludesEmbeddingFromSemanticSearchSource() {
        when(aiAvailability.isAvailable()).thenReturn(true);
        when(embeddingClient.embed(any()))
                .thenReturn(new EmbeddingResponse(List.of(0.1D)));

        @SuppressWarnings("unchecked")
        SearchHits<ContentSearchDocument> searchHits =
                mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);

        semanticSearchService.search("query", PageRequest.of(0, 20));

        ArgumentCaptor<NativeQuery> queryCaptor =
                ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(
                queryCaptor.capture(),
                eq(ContentSearchDocument.class)
        );

        assertThat(queryCaptor.getValue().getSourceFilter().getIncludes())
                .isEmpty();
        assertThat(queryCaptor.getValue().getSourceFilter().getExcludes())
                .containsExactly("embedding");
    }
}
