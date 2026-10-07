package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.condition.ContentTagCondition;
import com.mopl.content.search.condition.SemanticCandidateCondition;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.search.service.SemanticCandidateSearchService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "mopl.elasticsearch.reindex-on-startup=false",
        "mopl.ai.enabled=true",
        "ai.openai.api-key=test-api-key"
})
@Testcontainers
public class SemanticCandidateSearchIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl")
            .withUsername("mopl")
            .withPassword("mopl")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
            )
                    .withEnv("xpack.security.enabled", "false")
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentTagRepository contentTagRepository;

    @Autowired
    private ContentSearchRepository contentSearchRepository;

    @Autowired
    private ContentSearchIndexer contentSearchIndexer;

    @Autowired
    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @Autowired
    private SemanticCandidateSearchService semanticCandidateSearchService;

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

        // Content 삭제 전에 FK를 가진 ContentTag부터 정리
        contentTagRepository.deleteAllInBatch();
        contentRepository.deleteAllInBatch();

        stubEmbeddingClient();
    }

    @Test
    void searchPreservesSemanticOrderAndScore() {
        Content spaceMovie = createContent(
                ContentType.MOVIE,
                "Space Movie",
                "Astronauts travel through space to find a new home"
        );

        Content cookingMovie = createContent(
                ContentType.MOVIE,
                "Cooking Movie",
                "A chef opens a restaurant and creates new recipes"
        );

        contentRepository.saveAll(
                List.of(spaceMovie, cookingMovie)
        );

        contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "감동적인 우주 탐험 영화",
                        null,
                        10
                );

        assertThat(candidates).hasSize(2);

        // [검증] ES kNN 결과 순서 유지
        assertThat(candidates)
                .extracting(ContentCandidate::title)
                .containsExactly(
                        "Space Movie",
                        "Cooking Movie"
                );

        // [검증] SearchHit score가 Candidate까지 유지
        assertThat(candidates.get(0).semanticScore())
                .isGreaterThan(
                        candidates.get(1).semanticScore()
                );
    }

    @Test
    void searchFiltersByContentType() {
        Content movie = createContent(
                ContentType.MOVIE,
                "Movie Content",
                "movie description"
        );

        Content tvSeries = createContent(
                ContentType.TV_SERIES,
                "TV Content",
                "tv description"
        );

        contentRepository.saveAll(
                List.of(movie, tvSeries)
        );

        contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        SemanticCandidateCondition condition =
                new SemanticCandidateCondition(
                        ContentType.MOVIE,
                        List.of()
                );

        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "추천 콘텐츠",
                        condition,
                        10
                );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().contentId())
                .isEqualTo(movie.getId());
        assertThat(candidates.getFirst().type())
                .isEqualTo(ContentType.MOVIE);
    }

    @Test
    void searchFiltersByNestedTagAndValue() {
        Content sfMovie = createContent(
                ContentType.MOVIE,
                "SF Movie",
                "A science fiction adventure"
        );

        Content comedyMovie = createContent(
                ContentType.MOVIE,
                "Comedy Movie",
                "A light comedy story"
        );

        contentRepository.saveAll(
                List.of(sfMovie, comedyMovie)
        );

        contentTagRepository.saveAll(
                List.of(
                        new ContentTag(
                                sfMovie,
                                "GENRE",
                                "SF"
                        ),
                        new ContentTag(
                                comedyMovie,
                                "GENRE",
                                "COMEDY"
                        )
                )
        );

        contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        SemanticCandidateCondition condition =
                new SemanticCandidateCondition(
                        null,
                        List.of(
                                new ContentTagCondition(
                                        "GENRE",
                                        "SF"
                                )
                        )
                );

        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "우주를 배경으로 한 영화",
                        condition,
                        10
                );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().contentId())
                .isEqualTo(sfMovie.getId());

        // [검증] Candidate에도 ES tag 정보가 전달됨
        assertThat(candidates.getFirst().tags())
                .anySatisfy(tag -> {
                    assertThat(tag.tag())
                            .isEqualTo("GENRE");
                    assertThat(tag.value())
                            .isEqualTo("SF");
                });
    }

    @Test
    void searchRequiresAllTagConditions() {
        Content matchingMovie = createContent(
                ContentType.MOVIE,
                "Matching Movie",
                "A dramatic science fiction movie"
        );

        Content partialMovie = createContent(
                ContentType.MOVIE,
                "Partial Movie",
                "A science fiction movie"
        );

        contentRepository.saveAll(
                List.of(matchingMovie, partialMovie)
        );

        contentTagRepository.saveAll(
                List.of(
                        new ContentTag(
                                matchingMovie,
                                "GENRE",
                                "SF"
                        ),
                        new ContentTag(
                                matchingMovie,
                                "MOOD",
                                "EMOTIONAL"
                        ),
                        new ContentTag(
                                partialMovie,
                                "GENRE",
                                "SF"
                        )
                )
        );

        contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        SemanticCandidateCondition condition =
                new SemanticCandidateCondition(
                        ContentType.MOVIE,
                        List.of(
                                new ContentTagCondition(
                                        "GENRE",
                                        "SF"
                                ),
                                new ContentTagCondition(
                                        "MOOD",
                                        "EMOTIONAL"
                                )
                        )
                );

        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "감동적인 SF 영화",
                        condition,
                        10
                );

        // [검증] GENRE만 만족하는 Partial Movie는 제외
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().contentId())
                .isEqualTo(matchingMovie.getId());
    }

    @Test
    void searchExcludesSoftDeletedContentFromCandidates() {
        Content activeContent = createContent(
                ContentType.MOVIE,
                "Active Content",
                "active content"
        );

        Content deletedContent = createContent(
                ContentType.MOVIE,
                "Deleted Content",
                "deleted content"
        );

        contentRepository.saveAll(
                List.of(activeContent, deletedContent)
        );

        // [중요] 먼저 ES에는 두 문서 모두 색인
        contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        // [추가] ES 동기화 이벤트 없이 DB만 soft delete하여
        // MySQL/ES 일시 불일치 상황을 의도적으로 생성
        deletedContent.delete();
        contentRepository.saveAndFlush(deletedContent);

        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "추천 콘텐츠",
                        null,
                        10
                );

        assertThat(candidates)
                .extracting(ContentCandidate::contentId)
                .containsExactly(activeContent.getId());

        assertThat(candidates)
                .extracting(ContentCandidate::contentId)
                .doesNotContain(deletedContent.getId());
    }

    @Test
    void searchReturnsEmptyResultWhenIndexIsEmpty() {
        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "검색 결과가 없는 테스트",
                        null,
                        10
                );

        assertThat(candidates).isEmpty();
    }

    @Test
    void searchThrowsMoplExceptionWhenQueryIsBlank() {
        assertThatThrownBy(() ->
                semanticCandidateSearchService.search(
                        " ",
                        null,
                        10
                )
        )
                .isInstanceOf(MoplException.class)
                .satisfies(exception ->
                        assertThat(
                                ((MoplException) exception)
                                        .getErrorCode()
                        ).isEqualTo(
                                CommonErrorCode.INVALID_INPUT_VALUE
                        )
                );
    }

    @Test
    void searchThrowsMoplExceptionWhenSizeIsInvalid() {
        assertThatThrownBy(() ->
                semanticCandidateSearchService.search(
                        "검색어",
                        null,
                        0
                )
        )
                .isInstanceOf(MoplException.class)
                .satisfies(exception ->
                        assertThat(
                                ((MoplException) exception)
                                        .getErrorCode()
                        ).isEqualTo(
                                CommonErrorCode.INVALID_INPUT_VALUE
                        )
                );

        assertThatThrownBy(() ->
                semanticCandidateSearchService.search(
                        "검색어",
                        null,
                        10_001
                )
        )
                .isInstanceOf(MoplException.class)
                .satisfies(exception ->
                        assertThat(
                                ((MoplException) exception)
                                        .getErrorCode()
                        ).isEqualTo(
                                CommonErrorCode.INVALID_INPUT_VALUE
                        )
                );
    }

    @Test
    void searchThrowsMoplExceptionWhenTagConditionIsInvalid() {
        SemanticCandidateCondition condition =
                new SemanticCandidateCondition(
                        null,
                        List.of(
                                new ContentTagCondition(
                                        "",
                                        "SF"
                                )
                        )
                );

        assertThatThrownBy(() ->
                semanticCandidateSearchService.search(
                        "검색어",
                        condition,
                        10
                )
        )
                .isInstanceOf(MoplException.class)
                .satisfies(exception ->
                        assertThat(
                                ((MoplException) exception)
                                        .getErrorCode()
                        ).isEqualTo(
                                CommonErrorCode.INVALID_INPUT_VALUE
                        )
                );
    }

    @Test
    void searchThrowsMoplExceptionWhenTagConditionContainsNull() {
        SemanticCandidateCondition condition =
                new SemanticCandidateCondition(
                        null,
                        Collections.singletonList(null)
                );

        assertThatThrownBy(() ->
                semanticCandidateSearchService.search(
                        "검색어",
                        condition,
                        10
                )
        )
                .isInstanceOf(MoplException.class)
                .satisfies(exception ->
                        assertThat(
                                ((MoplException) exception)
                                        .getErrorCode()
                        ).isEqualTo(
                                CommonErrorCode.INVALID_INPUT_VALUE
                        )
                );
    }

    @Test
    void searchOverFetchesWhenTopCandidateIsStale() {
        Content deletedContent = createContent(
                ContentType.MOVIE,
                "Deleted Content",
                "Most similar content"
        );

        Content activeContent = createContent(
                ContentType.MOVIE,
                "Cooking Movie",
                "Less similar but active content"
        );

        contentRepository.saveAll(
                List.of(deletedContent, activeContent)
        );

        contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        // ES에는 남겨두고 MySQL에서만 soft delete
        deletedContent.delete();
        contentRepository.saveAndFlush(deletedContent);

        List<ContentCandidate> candidates =
                semanticCandidateSearchService.search(
                        "추천 콘텐츠",
                        null,
                        1
                );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().contentId())
                .isEqualTo(activeContent.getId());
    }

    // 색인용 Content와 검색 query에 서로 다른 벡터를 반환
    private void stubEmbeddingClient() {
        when(embeddingClient.embed(
                any(EmbeddingRequest.class)
        ))
                .thenAnswer(invocation -> {
                    EmbeddingRequest request = invocation.getArgument(0);

                    String input = request.input();

                    if (input.contains("Cooking Movie")) {
                        return new EmbeddingResponse(createVector(1));
                    }

                    return new EmbeddingResponse(createVector(0));
                });
    }

    private List<Double> createVector(int activeIndex) {
        List<Double> vector = new ArrayList<>(EMBEDDING_DIMENSIONS);

        for (int index = 0; index < EMBEDDING_DIMENSIONS; index++) {
            vector.add(
                    index == activeIndex
                            ? 1.0D
                            : 0.0D
            );
        }

        return vector;
    }

    private Content createContent(
            ContentType type,
            String title,
            String description
    ) {
        return new Content(
                type,
                title,
                description,
                null,
                ExternalSource.MANUAL,
                null,
                LocalDate.of(2026, 9, 22),
                new BigDecimal("100.0"),
                new BigDecimal("8.0"),
                100L
        );
    }
}
