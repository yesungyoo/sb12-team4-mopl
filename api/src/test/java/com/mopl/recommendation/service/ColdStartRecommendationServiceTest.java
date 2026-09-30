package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.recommendation.config.RecommendationProperties;
import com.mopl.recommendation.dto.RecommendationItem;

@ExtendWith(MockitoExtension.class)
class ColdStartRecommendationServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ContentRepository contentRepository;

    private RecommendationProperties recommendationProperties;

    private ColdStartRecommendationService coldStartRecommendationService;

    @BeforeEach
    void setUp() {
        recommendationProperties = new RecommendationProperties(30, 10, Duration.ofHours(6));
        coldStartRecommendationService = new ColdStartRecommendationService(
                elasticsearchOperations,
                contentRepository,
                recommendationProperties
        );
    }

    @Test
    void returnsEmptyWhenThereAreNoPopularContents() {
        @SuppressWarnings("unchecked")
        SearchHits<ContentSearchDocument> searchHits = mock(SearchHits.class);

        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ContentSearchDocument.class)))
                .thenReturn(searchHits);
        when(searchHits.isEmpty()).thenReturn(true);

        List<RecommendationItem> result = coldStartRecommendationService.recommend();

        assertThat(result).isEmpty();
    }

    @Test
    void returnsPopularContentsForColdStart() {
        UUID contentId = UUID.randomUUID();

        ContentSearchDocument document = mock(ContentSearchDocument.class);
        when(document.getId()).thenReturn(contentId.toString());
        when(document.getTags()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        SearchHit<ContentSearchDocument> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(document);

        @SuppressWarnings("unchecked")
        SearchHits<ContentSearchDocument> searchHits = mock(SearchHits.class);
        when(searchHits.isEmpty()).thenReturn(false);
        when(searchHits.getSearchHits()).thenReturn(List.of(searchHit));

        Content content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        when(content.getTitle()).thenReturn("Interstellar");
        when(content.getType()).thenReturn(ContentType.MOVIE);
        when(content.getExternalRating()).thenReturn(new BigDecimal("8.7"));
        when(content.getExternalPopularity()).thenReturn(new BigDecimal("120.0"));
        when(content.getExternalVoteCount()).thenReturn(5000L);

        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ContentSearchDocument.class)))
                .thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(List.of(contentId)))
                .thenReturn(List.of(content));

        List<RecommendationItem> result = coldStartRecommendationService.recommend();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().contentId()).isEqualTo(contentId);
        assertThat(result.getFirst().title()).isEqualTo("Interstellar");
        assertThat(result.getFirst().reason()).isEqualTo("현재 MOPL에서 인기 있는 콘텐츠입니다.");
    }

    @Test
    void excludesContentDeletedFromMysql() {
        UUID contentId = UUID.randomUUID();

        ContentSearchDocument document = mock(ContentSearchDocument.class);
        when(document.getId()).thenReturn(contentId.toString());

        @SuppressWarnings("unchecked")
        SearchHit<ContentSearchDocument> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(document);

        @SuppressWarnings("unchecked")
        SearchHits<ContentSearchDocument> searchHits = mock(SearchHits.class);
        when(searchHits.isEmpty()).thenReturn(false);
        when(searchHits.getSearchHits()).thenReturn(List.of(searchHit));

        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ContentSearchDocument.class)))
                .thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(List.of(contentId))).thenReturn(List.of());

        List<RecommendationItem> result = coldStartRecommendationService.recommend();

        assertThat(result).isEmpty();
    }

    @Test
    void fillsMissingRecommendationsFromNextPageWhenDeletedContentExists() {
        recommendationProperties = new RecommendationProperties(2, 2, Duration.ofHours(6));
        coldStartRecommendationService = new ColdStartRecommendationService(
                elasticsearchOperations,
                contentRepository,
                recommendationProperties
        );

        UUID deletedContentId = UUID.randomUUID();
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();

        ContentSearchDocument deletedDocument = mock(ContentSearchDocument.class);
        ContentSearchDocument firstDocument = mock(ContentSearchDocument.class);
        ContentSearchDocument secondDocument = mock(ContentSearchDocument.class);

        when(deletedDocument.getId()).thenReturn(deletedContentId.toString());
        when(firstDocument.getId()).thenReturn(firstContentId.toString());
        when(secondDocument.getId()).thenReturn(secondContentId.toString());
        when(firstDocument.getTags()).thenReturn(List.of());
        when(secondDocument.getTags()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        SearchHit<ContentSearchDocument> deletedSearchHit = mock(SearchHit.class);
        @SuppressWarnings("unchecked")
        SearchHit<ContentSearchDocument> firstSearchHit = mock(SearchHit.class);
        @SuppressWarnings("unchecked")
        SearchHit<ContentSearchDocument> secondSearchHit = mock(SearchHit.class);

        when(deletedSearchHit.getContent()).thenReturn(deletedDocument);
        when(firstSearchHit.getContent()).thenReturn(firstDocument);
        when(secondSearchHit.getContent()).thenReturn(secondDocument);

        @SuppressWarnings("unchecked")
        SearchHits<ContentSearchDocument> firstPageSearchHits = mock(SearchHits.class);
        @SuppressWarnings("unchecked")
        SearchHits<ContentSearchDocument> secondPageSearchHits = mock(SearchHits.class);

        when(firstPageSearchHits.isEmpty()).thenReturn(false);
        when(firstPageSearchHits.getSearchHits()).thenReturn(List.of(deletedSearchHit, firstSearchHit));
        when(secondPageSearchHits.isEmpty()).thenReturn(false);
        when(secondPageSearchHits.getSearchHits()).thenReturn(List.of(secondSearchHit));

        Content firstContent = mock(Content.class);
        when(firstContent.getId()).thenReturn(firstContentId);
        when(firstContent.getTitle()).thenReturn("Interstellar");
        when(firstContent.getType()).thenReturn(ContentType.MOVIE);
        when(firstContent.getExternalRating()).thenReturn(new BigDecimal("8.7"));
        when(firstContent.getExternalPopularity()).thenReturn(new BigDecimal("120.0"));
        when(firstContent.getExternalVoteCount()).thenReturn(5000L);

        Content secondContent = mock(Content.class);
        when(secondContent.getId()).thenReturn(secondContentId);
        when(secondContent.getTitle()).thenReturn("Inception");
        when(secondContent.getType()).thenReturn(ContentType.MOVIE);
        when(secondContent.getExternalRating()).thenReturn(new BigDecimal("8.5"));
        when(secondContent.getExternalPopularity()).thenReturn(new BigDecimal("110.0"));
        when(secondContent.getExternalVoteCount()).thenReturn(4500L);

        when(elasticsearchOperations.search(any(NativeQuery.class), eq(ContentSearchDocument.class)))
                .thenReturn(firstPageSearchHits, secondPageSearchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(List.of(deletedContentId, firstContentId)))
                .thenReturn(List.of(firstContent));
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(List.of(secondContentId)))
                .thenReturn(List.of(secondContent));

        List<RecommendationItem> result = coldStartRecommendationService.recommend();

        assertThat(result)
                .extracting(RecommendationItem::contentId)
                .containsExactly(firstContentId, secondContentId);
        verify(elasticsearchOperations, times(2))
                .search(any(NativeQuery.class), eq(ContentSearchDocument.class));
    }
}