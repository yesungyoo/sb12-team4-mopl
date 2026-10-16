package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;

@ExtendWith(MockitoExtension.class)
class ContentSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentSearchRepository contentSearchRepository;

    private ContentSearchService contentSearchService;

    @BeforeEach
    void setUp() {
        contentSearchService = new ContentSearchService(
                elasticsearchOperations,
                contentRepository,
                contentSearchRepository
        );
    }

    @Test
    void excludesEmbeddingFromGeneralSearchSource() {
        SearchHits<ContentSearchDocument> searchHits = emptySearchHits();
        when(elasticsearchOperations.search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);

        contentSearchService.search(
                new ContentSearchCondition(null, null, List.of()),
                null,
                null,
                43,
                "createdAt",
                "DESCENDING"
        );

        assertEmbeddingExcluded(captureQuery());
    }

    @Test
    void excludesEmbeddingFromIdLookupSource() {
        UUID contentId = UUID.randomUUID();
        SearchHits<ContentSearchDocument> searchHits = emptySearchHits();
        when(elasticsearchOperations.search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);

        assertThat(contentSearchService.findDocumentsByContentIds(
                List.of(contentId)
        )).isEmpty();

        NativeQuery query = captureQuery();
        assertEmbeddingExcluded(query);
        assertThat(query.getQuery().ids().values())
                .containsExactly(contentId.toString());
    }

    @SuppressWarnings("unchecked")
    private SearchHits<ContentSearchDocument> emptySearchHits() {
        SearchHits<ContentSearchDocument> searchHits =
                mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        return searchHits;
    }

    private NativeQuery captureQuery() {
        ArgumentCaptor<NativeQuery> queryCaptor =
                ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(
                queryCaptor.capture(),
                eq(ContentSearchDocument.class)
        );
        return queryCaptor.getValue();
    }

    private void assertEmbeddingExcluded(NativeQuery query) {
        assertThat(query.getSourceFilter().getIncludes()).isEmpty();
        assertThat(query.getSourceFilter().getExcludes())
                .containsExactly("embedding");
    }
}
