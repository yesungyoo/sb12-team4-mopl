package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "mopl.elasticsearch.reindex-on-startup=false"
})
@Testcontainers
public class ContentSearchIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl")
            .withUsername("mopl")
            .withPassword("mopl")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.8"))
                    .withEnv("xpack.security.enabled", "false")
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private ContentRepository contentRepository;

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
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
    }

    @BeforeEach
    void setUp() {
        contentSearchRepository.deleteAll();

        refreshSearchIndex();

        contentRepository.deleteAllInBatch();

        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(Collections.nCopies(EMBEDDING_DIMENSIONS, 0.01D)));
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

        contentRepository.saveAll(List.of(spiderMan, drama));

        long indexedCount = contentSearchIndexer.reindexAll();

        refreshSearchIndex();

        ContentSearchCondition condition = new ContentSearchCondition(
                ContentType.MOVIE,
                "Hero",
                List.of()
        );

        CursorResponse<ContentListItemResponse> response = contentSearchService.search(
                condition,
                null,
                null,
                10,
                "createdAt",
                "DESCENDING"
        );

        assertThat(indexedCount).isEqualTo(2);
        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().title())
                .isEqualTo("Spider Hero");
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

        // [추가] 첫 페이지
        CursorResponse<ContentListItemResponse> firstPage =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        2,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(firstPage.data()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(firstPage.nextIdAfter()).isNotBlank();

        // [추가] 첫 페이지가 반환한 cursor로 다음 페이지 조회
        CursorResponse<ContentListItemResponse> secondPage =
                contentSearchService.search(
                        condition,
                        firstPage.nextCursor(),
                        UUID.fromString(firstPage.nextIdAfter()),
                        2,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(secondPage.data()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();

        // [추가] 페이지 간 중복 콘텐츠가 없어야 함
        assertThat(secondPage.data().getFirst().id())
                .isNotIn(
                        firstPage.data().get(0).id(),
                        firstPage.data().get(1).id()
                );

        // [추가] 필터 전체 결과 수는 페이지와 무관하게 동일
        assertThat(firstPage.totalCount()).isEqualTo(3L);
        assertThat(secondPage.totalCount()).isEqualTo(3L);
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

        // [추가]
        // reindex 직후 검색 결과가 즉시 보이도록 refresh
        refreshSearchIndex();

        ContentSearchCondition condition = new ContentSearchCondition(
                ContentType.MOVIE,
                null,
                List.of()
        );

        // [추가]
        // 리뷰가 없으므로 세 콘텐츠 모두 averageRating = 0.0
        // 따라서 rate 정렬값이 동일하고 id가 실제 tie-breaker로 사용되어야 함
        CursorResponse<ContentListItemResponse> firstPage =
                contentSearchService.search(
                        condition,
                        null,
                        null,
                        2,
                        "rate",
                        "ASCENDING"
                );

        assertThat(firstPage.data()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(firstPage.nextIdAfter()).isNotBlank();

        CursorResponse<ContentListItemResponse> secondPage =
                contentSearchService.search(
                        condition,
                        firstPage.nextCursor(),
                        UUID.fromString(firstPage.nextIdAfter()),
                        2,
                        "rate",
                        "ASCENDING"
                );

        assertThat(secondPage.data()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();

        // [추가]
        // primary sort(rate)가 모두 같으므로 id ASC 보조 정렬 순서 검증
        List<UUID> expectedIds = List.of(
                        first.getId(),
                        second.getId(),
                        third.getId()
                )
                .stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        List<UUID> actualIds = List.of(
                firstPage.data().get(0).id(),
                firstPage.data().get(1).id(),
                secondPage.data().get(0).id()
        );

        // [추가]
        // 페이지 사이 중복/누락 없이 id tie-breaker 순서대로 조회되는지 검증
        assertThat(actualIds)
                .containsExactlyElementsOf(expectedIds)
                .doesNotHaveDuplicates();

        assertThat(firstPage.totalCount()).isEqualTo(3L);
        assertThat(secondPage.totalCount()).isEqualTo(3L);
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
                .indexOps(ContentSearchDocument.class)
                .refresh();
    }
}
