package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import com.mopl.recommendation.dto.RecommendationSectionItem;

@ExtendWith(MockitoExtension.class)
class RecommendationSectionSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ContentRepository contentRepository;

    private RecommendationSectionSearchService recommendationSectionSearchService;

    @BeforeEach
    void setUp() {
        recommendationSectionSearchService =
                new RecommendationSectionSearchService(
                        elasticsearchOperations,
                        contentRepository
                );
    }

    @Test
    void continuesSearchingWhenMysqlValidationReducesResults() {
        UUID staleContentId = UUID.randomUUID();
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();

        SearchHit<ContentSearchDocument> staleHit =
                mockSearchHit(staleContentId);

        SearchHit<ContentSearchDocument> firstHit =
                mockSearchHit(firstContentId);

        SearchHit<ContentSearchDocument> secondHit =
                mockSearchHit(secondContentId);

        SearchHits<ContentSearchDocument> firstPage =
                mockSearchHits(
                        List.of(
                                staleHit,
                                firstHit
                        )
                );

        SearchHits<ContentSearchDocument> secondPage =
                mockSearchHits(
                        List.of(secondHit)
                );

        Content firstContent =
                mockContent(
                        firstContentId,
                        "First",
                        ContentType.MOVIE
                );

        Content secondContent =
                mockContent(
                        secondContentId,
                        "Second",
                        ContentType.MOVIE
                );

        when(elasticsearchOperations.search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(
                firstPage,
                secondPage
        );

        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(
                        staleContentId,
                        firstContentId
                )
        )).thenReturn(
                List.of(firstContent)
        );

        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(secondContentId)
        )).thenReturn(
                List.of(secondContent)
        );

        List<RecommendationSectionItem> result =
                recommendationSectionSearchService
                        .findPopular(2);

        assertThat(result)
                .extracting(
                        RecommendationSectionItem::contentId
                )
                .containsExactly(
                        firstContentId,
                        secondContentId
                );

        verify(
                elasticsearchOperations,
                times(2)
        ).search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        );
    }

    @Test
    void continuesSearchingWhenPreviousSectionContentsAreExcluded() {
        UUID excludedContentId = UUID.randomUUID();
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();

        SearchHit<ContentSearchDocument> excludedHit =
                mockSearchHit(excludedContentId);

        SearchHit<ContentSearchDocument> firstHit =
                mockSearchHit(firstContentId);

        SearchHit<ContentSearchDocument> secondHit =
                mockSearchHit(secondContentId);

        SearchHits<ContentSearchDocument> firstPage =
                mockSearchHits(
                        List.of(
                                excludedHit,
                                firstHit
                        )
                );

        SearchHits<ContentSearchDocument> secondPage =
                mockSearchHits(
                        List.of(secondHit)
                );

        Content firstContent =
                mockContent(
                        firstContentId,
                        "First",
                        ContentType.MOVIE
                );

        Content secondContent =
                mockContent(
                        secondContentId,
                        "Second",
                        ContentType.MOVIE
                );

        when(elasticsearchOperations.search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(
                firstPage,
                secondPage
        );

        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(firstContentId)
        )).thenReturn(
                List.of(firstContent)
        );

        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(secondContentId)
        )).thenReturn(
                List.of(secondContent)
        );

        List<RecommendationSectionItem> result =
                recommendationSectionSearchService
                        .findPopular(
                                2,
                                Set.of(excludedContentId)
                        );

        assertThat(result)
                .extracting(
                        RecommendationSectionItem::contentId
                )
                .containsExactly(
                        firstContentId,
                        secondContentId
                )
                .doesNotContain(excludedContentId);

        ArgumentCaptor<NativeQuery> queryCaptor =
                ArgumentCaptor.forClass(NativeQuery.class);

        verify(
                elasticsearchOperations,
                times(2)
        ).search(
                queryCaptor.capture(),
                eq(ContentSearchDocument.class)
        );

        assertThat(queryCaptor.getAllValues())
                .allSatisfy(query -> assertThat(
                        query.getQuery().bool().mustNot()
                ).singleElement().satisfies(mustNot -> assertThat(
                        mustNot.ids().values()
                ).containsExactly(excludedContentId.toString())));
    }

    @Test
    void findByContentIdsPreservesRequestedOrderAndRemovesMissingContent() {
        UUID firstContentId = UUID.randomUUID();
        UUID missingContentId = UUID.randomUUID();
        UUID thirdContentId = UUID.randomUUID();

        SearchHit<ContentSearchDocument> firstHit =
                mockSearchHit(firstContentId);

        SearchHit<ContentSearchDocument> missingHit =
                mockSearchHit(missingContentId);

        SearchHit<ContentSearchDocument> thirdHit =
                mockSearchHit(thirdContentId);

        SearchHits<ContentSearchDocument> searchHits =
                mockSearchHits(
                        List.of(
                                thirdHit,
                                missingHit,
                                firstHit
                        )
                );

        Content firstContent =
                mockContent(
                        firstContentId,
                        "First",
                        ContentType.MOVIE
                );

        Content thirdContent =
                mockContent(
                        thirdContentId,
                        "Third",
                        ContentType.TV_SERIES
                );

        when(elasticsearchOperations.search(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);

        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(
                        firstContentId,
                        missingContentId,
                        thirdContentId
                )
        )).thenReturn(
                List.of(
                        thirdContent,
                        firstContent
                )
        );

        List<RecommendationSectionItem> result =
                recommendationSectionSearchService
                        .findByContentIds(
                                List.of(
                                        firstContentId,
                                        missingContentId,
                                        thirdContentId
                                )
                        );

        assertThat(result)
                .extracting(
                        RecommendationSectionItem::contentId
                )
                .containsExactly(
                        firstContentId,
                        thirdContentId
                );
    }

    @SuppressWarnings("unchecked")
    private SearchHits<ContentSearchDocument> mockSearchHits(
            List<SearchHit<ContentSearchDocument>> hits
    ) {
        SearchHits<ContentSearchDocument> searchHits =
                mock(SearchHits.class);

        lenient()
                .when(searchHits.isEmpty())
                .thenReturn(hits.isEmpty());

        when(searchHits.getSearchHits())
                .thenReturn(hits);

        return searchHits;
    }

    @SuppressWarnings("unchecked")
    private SearchHit<ContentSearchDocument> mockSearchHit(
            UUID contentId
    ) {
        ContentSearchDocument document =
                mock(ContentSearchDocument.class);

        when(document.getId())
                .thenReturn(contentId.toString());

        lenient()
                .when(document.getTags())
                .thenReturn(List.of());

        lenient()
                .when(document.getAverageRating())
                .thenReturn(0.0);

        lenient()
                .when(document.getReviewCount())
                .thenReturn(0L);

        lenient()
                .when(document.getWatcherCount())
                .thenReturn(0L);

        lenient()
                .when(document.getCreatedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                10,
                                1,
                                12,
                                0
                        )
                );

        SearchHit<ContentSearchDocument> searchHit =
                mock(SearchHit.class);

        when(searchHit.getContent())
                .thenReturn(document);

        return searchHit;
    }

    private Content mockContent(
            UUID contentId,
            String title,
            ContentType contentType
    ) {
        Content content =
                mock(Content.class);

        when(content.getId())
                .thenReturn(contentId);

        lenient()
                .when(content.getTitle())
                .thenReturn(title);

        lenient()
                .when(content.getType())
                .thenReturn(contentType);

        lenient()
                .when(content.getThumbnailUrl())
                .thenReturn(
                        "https://example.com/"
                                + contentId
                                + ".jpg"
                );

        lenient()
                .when(content.getCreatedAt())
                .thenReturn(
                        LocalDateTime.of(
                                2026,
                                10,
                                1,
                                12,
                                0
                        )
                );

        return content;
    }
}
