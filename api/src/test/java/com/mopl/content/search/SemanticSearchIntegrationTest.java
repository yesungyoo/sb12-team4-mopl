package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.search.service.SemanticSearchService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "mopl.elasticsearch.reindex-on-startup=false",
        "mopl.ai.enabled=true",
        "ai.openai.api-key=test-api-key"
})
@Testcontainers
public class SemanticSearchIntegrationTest {

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
    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @Autowired
    private SemanticSearchService semanticSearchService;

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
        contentRepository.deleteAllInBatch();
    }

    @Test
    void searchReturnsMostSimilarContentFirst() {
        Content spaceMovie = createContent(
                "Space Journey",
                "Astronauts travel through space to find a new home for humanity"
        );

        Content cookingMovie = createContent(
                "Cooking Life",
                "A chef opens a small restaurant and discovers new recipes"
        );

        contentRepository.saveAll(List.of(spaceMovie, cookingMovie));

        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            String input = request.input();

            if (input.contains("Space Journey")) {
                return new EmbeddingResponse(createVector(0));
            }

            if (input.contains("Cooking Life")) {
                return new EmbeddingResponse(createVector(1));
            }

            return new EmbeddingResponse(createVector(0));
        });

        long indexedCount = contentSearchIndexer.reindexAll();
        contentEmbeddingIndexer.reindexAll();

        ContentListResponse response =
                semanticSearchService.search("감동적인 우주 탐험 영화", PageRequest.of(0, 10));

        assertThat(indexedCount).isEqualTo(2);
        assertThat(response.contents()).hasSize(2);
        assertThat(response.contents().getFirst().title()).isEqualTo("Space Journey");
        assertThat(response.contents().get(1).title()).isEqualTo("Cooking Life");
    }

    @Test
    void indexPreservesExistingEmbeddingWhenRegenerationFails() {
        Content content = createContent(
                "Original Title",
                "Original description"
        );

        contentRepository.saveAndFlush(content);
        contentSearchIndexer.index(content);

        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(createVector(0)));

        contentEmbeddingIndexer.index(content);

        ContentSearchDocument embeddedDocument =
                contentSearchRepository
                        .findById(content.getId().toString())
                        .orElseThrow();

        assertThat(embeddedDocument.getEmbedding()).isNotEmpty();

        List<Float> existingEmbedding =
                List.copyOf(embeddedDocument.getEmbedding());

        content.update(
                null,
                "Updated Title",
                null,
                null,
                null
        );
        contentRepository.saveAndFlush(content);

        contentSearchIndexer.index(content);

        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("embedding failure"));

        contentEmbeddingIndexer.index(content);

        ContentSearchDocument updatedDocument =
                contentSearchRepository
                        .findById(content.getId().toString())
                        .orElseThrow();

        assertThat(updatedDocument.getTitle())
                .isEqualTo("Updated Title");
        assertThat(updatedDocument.getEmbedding())
                .containsExactlyElementsOf(existingEmbedding);
    }

    @Test
    void embeddingIndexDoesNotRecreateDeletedElasticsearchDocument() {
        Content content = createContent(
                "Deleted Elasticsearch Document",
                "Content whose search document is deleted"
        );

        contentRepository.saveAndFlush(content);
        contentSearchIndexer.index(content);

        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(createVector(0)));

        contentEmbeddingIndexer.index(content);

        String documentId =
                content.getId().toString();

        ContentSearchDocument embeddedDocument =
                contentSearchRepository
                        .findById(documentId)
                        .orElseThrow();

        assertThat(embeddedDocument.getEmbedding())
                .isNotEmpty();

        contentSearchRepository.deleteById(documentId);

        assertThat(contentSearchRepository.existsById(documentId))
                .isFalse();

        clearInvocations(embeddingClient);

        contentEmbeddingIndexer.index(content);

        assertThat(contentSearchRepository.existsById(documentId))
                .isFalse();

        verifyNoInteractions(embeddingClient);
    }

    @Test
    void searchReturnsEmptyResultWhenIndexIsEmpty() {
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(createVector(0)));

        ContentListResponse response =
                semanticSearchService.search("검색 결과가 없는 테스트", PageRequest.of(0, 10));

        assertThat(response.contents()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    private List<Double> createVector(int activeIndex) {
        List<Double> vector = new ArrayList<>(EMBEDDING_DIMENSIONS);

        for (int index = 0; index < EMBEDDING_DIMENSIONS; index++) {
            vector.add(index == activeIndex ? 1.0D : 0.0D);
        }

        return vector;
    }

    private Content createContent(String title, String description) {
        return new Content(
                ContentType.MOVIE,
                title,
                description,
                null,
                ExternalSource.MANUAL,
                null,
                LocalDate.of(2026, 9, 21),
                new BigDecimal("100.0"),
                new BigDecimal("8.0"),
                100L
        );
    }
}
