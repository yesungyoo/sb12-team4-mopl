package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "mopl.elasticsearch.reindex-on-startup=false"
})
@Testcontainers
class ContentSearchIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("mopl")
                    .withUsername("mopl")
                    .withPassword("mopl")
                    .withStartupTimeout(
                            Duration.ofMinutes(5)
                    );

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse(
                            "docker.elastic.co/elasticsearch/elasticsearch:8.18.8"
                    )
            )
                    .withEnv(
                            "xpack.security.enabled",
                            "false"
                    )
                    .withStartupTimeout(
                            Duration.ofMinutes(5)
                    );

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentTagRepository contentTagRepository;

    @Autowired
    private ContentSearchRepository contentSearchRepository;

    @Autowired
    private ContentSearchIndexer contentSearchIndexer;

    @Autowired
    private ContentSearchService contentSearchService;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                MYSQL::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                MYSQL::getUsername
        );

        registry.add(
                "spring.datasource.password",
                MYSQL::getPassword
        );

        registry.add(
                "spring.elasticsearch.uris",
                ELASTICSEARCH::getHttpHostAddress
        );
    }

    @BeforeEach
    void setUp() {
        contentSearchRepository.deleteAll();
        refreshSearchIndex();

        contentTagRepository.deleteAllInBatch();
        contentRepository.deleteAllInBatch();

        when(
                embeddingClient.embed(
                        any(EmbeddingRequest.class)
                )
        ).thenReturn(
                new EmbeddingResponse(
                        Collections.nCopies(
                                EMBEDDING_DIMENSIONS,
                                0.01D
                        )
                )
        );
    }

    @Test
    void syncDataPreservesEmbeddingAndBulkUpsertsNewContent() {
        Content existingContent = createContent(
                ContentType.MOVIE,
                "동기화 전 제목",
                "기존 설명",
                LocalDate.of(2025, 1, 1),
                new BigDecimal("100.0"),
                new BigDecimal("7.0")
        );
        contentRepository.saveAndFlush(existingContent);

        List<Double> existingEmbedding = Collections.nCopies(
                EMBEDDING_DIMENSIONS,
                0.25D
        );
        contentSearchRepository.save(
                ContentSearchDocument.from(
                        existingContent,
                        List.of(),
                        existingEmbedding
                )
        );
        refreshSearchIndex();

        ContentSearchDocument storedDocument = contentSearchRepository
                .findById(existingContent.getId().toString())
                .orElseThrow();
        assertThat(storedDocument.getEmbedding())
                .hasSize(EMBEDDING_DIMENSIONS)
                .allMatch(value -> value.equals(0.25F));

        Query idOnlyQuery = Query.findAll();
        idOnlyQuery.addSourceFilter(new FetchSourceFilter(
                null,
                new String[]{"id"},
                null
        ));
        try (SearchHitsIterator<ContentSearchDocument> hits =
                     elasticsearchOperations.searchForStream(
                             idOnlyQuery,
                             ContentSearchDocument.class
                     )) {
            assertThat(hits.hasNext()).isTrue();
            SearchHit<ContentSearchDocument> hit = hits.next();
            assertThat(hit.getId())
                    .isEqualTo(existingContent.getId().toString());
            assertThat(hit.getContent().getEmbedding()).isNull();
        }

        existingContent.update(
                ContentType.MOVIE,
                "동기화 후 제목",
                "최신 설명",
                null,
                LocalDate.of(2026, 1, 1)
        );
        contentRepository.saveAndFlush(existingContent);

        Content newContent = createContent(
                ContentType.TV_SERIES,
                "신규 콘텐츠",
                "신규 설명",
                LocalDate.of(2026, 2, 1),
                new BigDecimal("200.0"),
                new BigDecimal("8.0")
        );
        contentRepository.saveAndFlush(newContent);

        long indexedCount = contentSearchIndexer.reindexAll();

        ContentSearchDocument updatedDocument = contentSearchRepository
                .findById(existingContent.getId().toString())
                .orElseThrow();
        ContentSearchDocument newDocument = contentSearchRepository
                .findById(newContent.getId().toString())
                .orElseThrow();

        assertThat(indexedCount).isEqualTo(2L);
        assertThat(updatedDocument.getTitle())
                .isEqualTo("동기화 후 제목");
        assertThat(updatedDocument.getDescription())
                .isEqualTo("최신 설명");
        assertThat(updatedDocument.getEmbedding())
                .hasSize(EMBEDDING_DIMENSIONS)
                .allMatch(value -> value.equals(0.25F));
        assertThat(newDocument.getTitle())
                .isEqualTo("신규 콘텐츠");
        assertThat(newDocument.getEmbedding()).isNull();
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void syncDataDeletesElasticsearchOnlyAndSoftDeletedDocuments() {
        Content activeContent = createContent(
                ContentType.MOVIE,
                "활성 콘텐츠",
                "활성 설명",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("300.0"),
                new BigDecimal("8.0")
        );
        Content softDeletedContent = createContent(
                ContentType.MOVIE,
                "Soft Delete 콘텐츠",
                "삭제 설명",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("200.0"),
                new BigDecimal("7.0")
        );
        Content elasticsearchOnlyContent = createContent(
                ContentType.MOVIE,
                "Elasticsearch 전용 콘텐츠",
                "DB 삭제 설명",
                LocalDate.of(2026, 1, 3),
                new BigDecimal("100.0"),
                new BigDecimal("6.0")
        );

        contentRepository.saveAllAndFlush(List.of(
                activeContent,
                softDeletedContent,
                elasticsearchOnlyContent
        ));
        contentSearchIndexer.reindexAll();

        UUID elasticsearchOnlyContentId =
                elasticsearchOnlyContent.getId();
        softDeletedContent.delete();
        contentRepository.saveAndFlush(softDeletedContent);
        contentRepository.deleteById(elasticsearchOnlyContentId);
        contentRepository.flush();

        long indexedCount = contentSearchIndexer.reindexAll();

        assertThat(indexedCount).isEqualTo(1L);
        assertThat(contentSearchRepository.existsById(
                activeContent.getId().toString()
        )).isTrue();
        assertThat(contentSearchRepository.existsById(
                softDeletedContent.getId().toString()
        )).isFalse();
        assertThat(contentSearchRepository.existsById(
                elasticsearchOnlyContentId.toString()
        )).isFalse();
    }

    @Test
    void searchByKeywordAndType() {
        Content spiderMan = createContent(
                ContentType.MOVIE,
                "Spider Hero",
                "A hero protects the city",
                LocalDate.of(2026, 7, 29),
                new BigDecimal("700.0"),
                new BigDecimal("8.0")
        );

        Content drama = createContent(
                ContentType.TV_SERIES,
                "Hero Drama",
                "A long running television drama",
                LocalDate.of(2025, 3, 15),
                new BigDecimal("300.0"),
                new BigDecimal("7.0")
        );

        contentRepository.saveAll(
                List.of(
                        spiderMan,
                        drama
                )
        );

        long indexedCount =
                contentSearchIndexer.reindexAll();

        refreshSearchIndex();

        ContentSearchCondition condition =
                new ContentSearchCondition(
                        ContentType.MOVIE,
                        "Hero",
                        List.of()
                );

        CursorResponse<ContentListItemResponse> response =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        10,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(indexedCount)
                .isEqualTo(2);

        assertThat(response.totalCount())
                .isEqualTo(1);

        assertThat(response.data())
                .hasSize(1);

        assertThat(
                response.data()
                        .getFirst()
                        .title()
        ).isEqualTo(
                "Spider Hero"
        );
    }

    @Test
    void searchMatchesTitlePrefix() {
        Content spiderMan = createContent(
                ContentType.MOVIE,
                "스파이더맨: 브랜드 뉴 데이",
                "새로운 스파이더맨 영화",
                LocalDate.of(2026, 7, 29),
                new BigDecimal("700.0"),
                new BigDecimal("8.0")
        );

        contentRepository.saveAndFlush(
                spiderMan
        );

        contentSearchIndexer.reindexAll();
        refreshSearchIndex();

        List<String> keywords = List.of(
                "스파",
                "스파이더",
                "스파이더맨",
                "브랜드",
                "브랜드 뉴"
        );

        for (String keyword : keywords) {
            ContentSearchCondition condition =
                    new ContentSearchCondition(
                            ContentType.MOVIE,
                            keyword,
                            List.of()
                    );

            CursorResponse<ContentListItemResponse> response =
                    contentSearchService.search(
                            condition,
                            null,
                            null,
                            10,
                            "createdAt",
                            "DESCENDING"
                    );

            assertThat(response.data())
                    .extracting(
                            ContentListItemResponse::id
                    )
                    .contains(
                            spiderMan.getId()
                    );
        }
    }

    @Test
    void searchFiltersByTagsIn() {
        Content sfMovie = createContent(
                ContentType.MOVIE,
                "SF Movie",
                "science fiction",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("500.0"),
                new BigDecimal("8.0")
        );

        Content dramaMovie = createContent(
                ContentType.MOVIE,
                "Drama Movie",
                "drama",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("400.0"),
                new BigDecimal("7.0")
        );

        contentRepository.saveAndFlush(
                sfMovie
        );

        contentRepository.saveAndFlush(
                dramaMovie
        );

        contentTagRepository.save(
                new ContentTag(
                        sfMovie,
                        "GENRE",
                        "SF"
                )
        );

        contentTagRepository.save(
                new ContentTag(
                        dramaMovie,
                        "GENRE",
                        "DRAMA"
                )
        );

        contentSearchIndexer.reindexAll();
        refreshSearchIndex();

        ContentSearchCondition condition =
                new ContentSearchCondition(
                        ContentType.MOVIE,
                        null,
                        List.of("SF")
                );

        CursorResponse<ContentListItemResponse> response =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        10,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(response.data())
                .extracting(
                        ContentListItemResponse::id
                )
                .containsExactly(
                        sfMovie.getId()
                );

        assertThat(
                response.data()
                        .getFirst()
                        .tags()
        ).contains(
                "SF"
        );
    }

    @Test
    void searchMatchesAnyTagsIn() {
        Content sfMovie = createContent(
                ContentType.MOVIE,
                "SF Movie",
                "science fiction",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("500.0"),
                new BigDecimal("8.0")
        );

        Content dramaMovie = createContent(
                ContentType.MOVIE,
                "Drama Movie",
                "drama",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("400.0"),
                new BigDecimal("7.0")
        );

        Content actionMovie = createContent(
                ContentType.MOVIE,
                "Action Movie",
                "action",
                LocalDate.of(2026, 1, 3),
                new BigDecimal("300.0"),
                new BigDecimal("6.0")
        );

        contentRepository.saveAndFlush(
                sfMovie
        );

        contentRepository.saveAndFlush(
                dramaMovie
        );

        contentRepository.saveAndFlush(
                actionMovie
        );

        contentTagRepository.save(
                new ContentTag(
                        sfMovie,
                        "GENRE",
                        "SF"
                )
        );

        contentTagRepository.save(
                new ContentTag(
                        dramaMovie,
                        "GENRE",
                        "DRAMA"
                )
        );

        contentTagRepository.save(
                new ContentTag(
                        actionMovie,
                        "GENRE",
                        "ACTION"
                )
        );

        contentSearchIndexer.reindexAll();
        refreshSearchIndex();

        ContentSearchCondition condition =
                new ContentSearchCondition(
                        ContentType.MOVIE,
                        null,
                        List.of(
                                "SF",
                                "DRAMA"
                        )
                );

        CursorResponse<ContentListItemResponse> response =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        10,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(response.data())
                .extracting(
                        ContentListItemResponse::id
                )
                .containsExactlyInAnyOrder(
                        sfMovie.getId(),
                        dramaMovie.getId()
                );
    }

    // [#98 추가]
    // ES에는 남아 있지만 MySQL에서는 soft-delete된 콘텐츠가
    // 첫 페이지에 끼어도 활성 콘텐츠로 페이지를 정상 보충한다.
    @Test
    void searchRefillsPageWhenElasticsearchContainsSoftDeletedContent() {
        Content first = createContent(
                ContentType.MOVIE,
                "Active Movie 1",
                "movie",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("100.0"),
                new BigDecimal("7.0")
        );

        Content second = createContent(
                ContentType.MOVIE,
                "Active Movie 2",
                "movie",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("200.0"),
                new BigDecimal("8.0")
        );

        Content third = createContent(
                ContentType.MOVIE,
                "Stale Movie",
                "movie",
                LocalDate.of(2026, 1, 3),
                new BigDecimal("300.0"),
                new BigDecimal("9.0")
        );

        List<Content> contents =
                contentRepository.saveAllAndFlush(
                        List.of(
                                first,
                                second,
                                third
                        )
                );

        contentSearchIndexer.reindexAll();
        refreshSearchIndex();

        // rate는 모두 review가 없어서 0.0이고,
        // tie-breaker가 id ASC이므로 가장 작은 id를
        // stale 문서로 만들어 첫 hit에 위치시킨다.
        Content staleContent =
                contents.stream()
                        .min(
                                Comparator.comparing(
                                        content ->
                                                content.getId()
                                                        .toString()
                                )
                        )
                        .orElseThrow();

        staleContent.delete();

        contentRepository.saveAndFlush(
                staleContent
        );

        List<UUID> expectedActiveIds =
                contents.stream()
                        .filter(content ->
                                !content.getId()
                                        .equals(
                                                staleContent.getId()
                                        )
                        )
                        .map(Content::getId)
                        .sorted(
                                Comparator.comparing(
                                        UUID::toString
                                )
                        )
                        .toList();

        ContentSearchCondition condition =
                new ContentSearchCondition(
                        ContentType.MOVIE,
                        null,
                        List.of()
                );

        CursorResponse<ContentListItemResponse> response =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        2,
                        "rate",
                        "ASCENDING"
                );

        assertThat(response.data())
                .extracting(
                        ContentListItemResponse::id
                )
                .containsExactlyElementsOf(
                        expectedActiveIds
                );

        assertThat(response.data())
                .hasSize(2);

        assertThat(response.hasNext())
                .isFalse();

        assertThat(response.nextCursor())
                .isNull();

        assertThat(response.nextIdAfter())
                .isNull();

        assertThat(response.totalCount())
                .isEqualTo(2L);

        // search 중 발견한 stale ES 문서도 정리됐는지 확인
        refreshSearchIndex();

        assertThat(
                contentSearchRepository.existsById(
                        staleContent.getId()
                                .toString()
                )
        ).isFalse();
    }

    @Test
    void searchUsesCursorForNextPage() {
        Content first = createContent(
                ContentType.MOVIE,
                "Movie A",
                "movie",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("100.0"),
                new BigDecimal("4.0")
        );

        Content second = createContent(
                ContentType.MOVIE,
                "Movie B",
                "movie",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("200.0"),
                new BigDecimal("4.0")
        );

        Content third = createContent(
                ContentType.MOVIE,
                "Movie C",
                "movie",
                LocalDate.of(2026, 1, 3),
                new BigDecimal("300.0"),
                new BigDecimal("4.0")
        );

        contentRepository.saveAndFlush(first);
        contentRepository.saveAndFlush(second);
        contentRepository.saveAndFlush(third);

        contentSearchIndexer.reindexAll();
        refreshSearchIndex();

        ContentSearchCondition condition =
                new ContentSearchCondition(
                        ContentType.MOVIE,
                        null,
                        List.of()
                );

        CursorResponse<ContentListItemResponse> firstPage =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        2,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(firstPage.data())
                .hasSize(2);

        assertThat(firstPage.hasNext())
                .isTrue();

        assertThat(firstPage.nextCursor())
                .isNotBlank();

        assertThat(firstPage.nextIdAfter())
                .isNotBlank();

        CursorResponse<ContentListItemResponse> secondPage =
                contentSearchService.search(
                        condition,
                        firstPage.nextCursor(),
                        UUID.fromString(
                                firstPage.nextIdAfter()
                        ),
                        2,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(secondPage.data())
                .hasSize(1);

        assertThat(secondPage.hasNext())
                .isFalse();

        assertThat(
                secondPage.data()
                        .getFirst()
                        .id()
        ).isNotIn(
                firstPage.data()
                        .get(0)
                        .id(),
                firstPage.data()
                        .get(1)
                        .id()
        );

        assertThat(firstPage.totalCount())
                .isEqualTo(3L);

        assertThat(secondPage.totalCount())
                .isEqualTo(3L);
    }

    @Test
    void searchUsesIdAsTieBreakerWhenSortValuesAreEqual() {
        Content first = createContent(
                ContentType.MOVIE,
                "Tie Breaker Movie 1",
                "movie",
                LocalDate.of(2026, 1, 1),
                new BigDecimal("100.0"),
                new BigDecimal("7.0")
        );

        Content second = createContent(
                ContentType.MOVIE,
                "Tie Breaker Movie 2",
                "movie",
                LocalDate.of(2026, 1, 2),
                new BigDecimal("200.0"),
                new BigDecimal("8.0")
        );

        Content third = createContent(
                ContentType.MOVIE,
                "Tie Breaker Movie 3",
                "movie",
                LocalDate.of(2026, 1, 3),
                new BigDecimal("300.0"),
                new BigDecimal("9.0")
        );

        contentRepository.saveAndFlush(first);
        contentRepository.saveAndFlush(second);
        contentRepository.saveAndFlush(third);

        contentSearchIndexer.reindexAll();
        refreshSearchIndex();

        ContentSearchCondition condition =
                new ContentSearchCondition(
                        ContentType.MOVIE,
                        null,
                        List.of()
                );

        CursorResponse<ContentListItemResponse> firstPage =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        2,
                        "rate",
                        "ASCENDING"
                );

        assertThat(firstPage.data())
                .hasSize(2);

        assertThat(firstPage.hasNext())
                .isTrue();

        assertThat(firstPage.nextCursor())
                .isNotBlank();

        assertThat(firstPage.nextIdAfter())
                .isNotBlank();

        CursorResponse<ContentListItemResponse> secondPage =
                contentSearchService.search(
                        condition,
                        firstPage.nextCursor(),
                        UUID.fromString(
                                firstPage.nextIdAfter()
                        ),
                        2,
                        "rate",
                        "ASCENDING"
                );

        assertThat(secondPage.data())
                .hasSize(1);

        assertThat(secondPage.hasNext())
                .isFalse();

        List<UUID> expectedIds =
                List.of(
                                first.getId(),
                                second.getId(),
                                third.getId()
                        )
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        UUID::toString
                                )
                        )
                        .toList();

        List<UUID> actualIds = List.of(
                firstPage.data()
                        .get(0)
                        .id(),
                firstPage.data()
                        .get(1)
                        .id(),
                secondPage.data()
                        .get(0)
                        .id()
        );

        assertThat(actualIds)
                .containsExactlyElementsOf(
                        expectedIds
                )
                .doesNotHaveDuplicates();

        assertThat(firstPage.totalCount())
                .isEqualTo(3L);

        assertThat(secondPage.totalCount())
                .isEqualTo(3L);
    }

    private Content createContent(
            ContentType type,
            String title,
            String description,
            LocalDate releaseDate,
            BigDecimal popularity,
            BigDecimal rating
    ) {
        return new Content(
                type,
                title,
                description,
                null,
                ExternalSource.MANUAL,
                null,
                releaseDate,
                popularity,
                rating,
                100L
        );
    }

    private void refreshSearchIndex() {
        elasticsearchOperations
                .indexOps(
                        ContentSearchDocument.class
                )
                .refresh();
    }
}
