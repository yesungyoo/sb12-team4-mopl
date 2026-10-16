package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.MultiGetItem;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

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
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private SemanticSearchService semanticSearchService;

    @Autowired
    private AiProperties aiProperties;

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
        var indexOperations = elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        );
        if (indexOperations.exists()) {
            indexOperations.delete();
        }
        indexOperations.createWithMapping();
        contentRepository.deleteAllInBatch();
    }

    @Test
    void metadataLookupUsesRealtimeMultiGetWithoutLoadingEmbedding() {
        Content content = contentRepository.saveAndFlush(createContent(
                "Realtime mget 콘텐츠",
                "refresh 없이 메타데이터를 조회하는 문서"
        ));
        ContentSearchDocument document = ContentSearchDocument.from(
                content,
                List.of(),
                createVector(0)
        );
        ReflectionTestUtils.setField(
                document,
                "contentInputHash",
                "content-input-hash"
        );
        ReflectionTestUtils.setField(
                document,
                "embeddingInputHash",
                "embedding-input-hash"
        );
        ReflectionTestUtils.setField(
                document,
                "embeddingModel",
                "embedding-model"
        );
        indexWithoutRefresh(document);

        ContentSearchDocument metadata = ReflectionTestUtils.invokeMethod(
                contentEmbeddingIndexer,
                "getDocumentMetadata",
                content.getId()
        );

        assertThat(metadata).isNotNull();
        assertThat(metadata.getId()).isEqualTo(content.getId().toString());
        assertThat(metadata.getContentInputHash())
                .isEqualTo("content-input-hash");
        assertThat(metadata.getEmbeddingInputHash())
                .isEqualTo("embedding-input-hash");
        assertThat(metadata.getEmbeddingModel())
                .isEqualTo("embedding-model");
        assertThat(metadata.getEmbedding()).isNull();
    }

    @Test
    void metadataLookupReturnsNullWhenDocumentDoesNotExist() {
        ContentSearchDocument metadata = ReflectionTestUtils.invokeMethod(
                contentEmbeddingIndexer,
                "getDocumentMetadata",
                UUID.randomUUID()
        );

        assertThat(metadata).isNull();
    }

    @Test
    void metadataLookupFailsWhenIndexDoesNotExist() {
        elasticsearchOperations.indexOps(ContentSearchDocument.class).delete();
        UUID contentId = UUID.randomUUID();
        List<MultiGetItem<ContentSearchDocument>> items =
                elasticsearchOperations.multiGet(
                        Query.multiGetQuery(List.of(contentId.toString())),
                        ContentSearchDocument.class,
                        elasticsearchOperations.getIndexCoordinatesFor(
                                ContentSearchDocument.class
                        )
                );

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.isFailed()).isTrue();
            assertThat(item.getItem()).isNull();
            assertThat(item.getFailure()).isNotNull();
        });

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                contentEmbeddingIndexer,
                "getDocumentMetadata",
                contentId
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Elasticsearch embedding 메타데이터 조회에 실패했습니다."
                );
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
    void ordinaryIndexingPreservesEmbeddingAndMetadataWhileUpdatingContentFields() {
        Content content = createContent(
                "Original Indexed Title",
                "Original indexed description"
        );
        contentRepository.saveAndFlush(content);
        contentSearchIndexer.index(content);

        List<Double> generatedEmbedding = createVector(2);
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(generatedEmbedding));
        contentEmbeddingIndexer.index(content);

        ContentSearchDocument beforeUpdate = contentSearchRepository
                .findById(content.getId().toString())
                .orElseThrow();
        String previousContentInputHash = beforeUpdate.getContentInputHash();
        String embeddingInputHash = beforeUpdate.getEmbeddingInputHash();
        String embeddingModel = beforeUpdate.getEmbeddingModel();
        List<Float> existingEmbedding = List.copyOf(beforeUpdate.getEmbedding());

        content.update(
                null,
                "Updated Indexed Title",
                "Updated indexed description",
                null,
                null
        );
        contentRepository.saveAndFlush(content);
        contentSearchIndexer.index(content);

        ContentSearchDocument afterUpdate = contentSearchRepository
                .findById(content.getId().toString())
                .orElseThrow();

        assertThat(afterUpdate.getTitle()).isEqualTo("Updated Indexed Title");
        assertThat(afterUpdate.getDescription())
                .isEqualTo("Updated indexed description");
        assertThat(afterUpdate.getContentInputHash())
                .isNotEqualTo(previousContentInputHash);
        assertThat(afterUpdate.getEmbedding())
                .containsExactlyElementsOf(existingEmbedding);
        assertThat(afterUpdate.getEmbeddingInputHash())
                .isEqualTo(embeddingInputHash);
        assertThat(afterUpdate.getEmbeddingModel())
                .isEqualTo(embeddingModel);
    }

    @Test
    void delayedEmbeddingForOldInputCannotOverwriteLatestEmbedding() throws Exception {
        String oldTitle = "Old Embedding Input Title";
        String latestTitle = "Latest Embedding Input Title";
        Content content = createContent(oldTitle, "Original description");
        contentRepository.saveAndFlush(content);
        contentSearchIndexer.index(content);

        String oldInputHash = contentSearchRepository
                .findById(content.getId().toString())
                .orElseThrow()
                .getContentInputHash();
        CountDownLatch oldEmbeddingStarted = new CountDownLatch(1);
        CountDownLatch allowOldEmbeddingToFinish = new CountDownLatch(1);
        AtomicBoolean oldEmbeddingReturned = new AtomicBoolean(false);
        List<Double> oldEmbedding = createVector(0);
        List<Double> latestEmbedding = createVector(1);

        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenAnswer(invocation -> {
                    EmbeddingRequest request = invocation.getArgument(0);
                    if (request.input().contains(oldTitle)) {
                        oldEmbeddingStarted.countDown();
                        if (!allowOldEmbeddingToFinish.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Timed out waiting to release old embedding"
                            );
                        }
                        oldEmbeddingReturned.set(true);
                        return new EmbeddingResponse(oldEmbedding);
                    }
                    return new EmbeddingResponse(latestEmbedding);
                });

        CompletableFuture<Void> oldEmbeddingTask = CompletableFuture.runAsync(
                () -> contentEmbeddingIndexer.index(content)
        );

        try {
            assertThat(oldEmbeddingStarted.await(30, TimeUnit.SECONDS))
                    .isTrue();

            content.update(
                    null,
                    latestTitle,
                    "Latest description",
                    null,
                    null
            );
            contentRepository.saveAndFlush(content);
            contentSearchIndexer.index(content);
            contentEmbeddingIndexer.index(content);
        } finally {
            allowOldEmbeddingToFinish.countDown();
            oldEmbeddingTask.get(30, TimeUnit.SECONDS);
        }

		assertThat(oldEmbeddingReturned.get())
			.as("the stale embedding request should return its vector")
			.isTrue();

        ContentSearchDocument latestDocument = contentSearchRepository
                .findById(content.getId().toString())
                .orElseThrow();
        assertThat(latestDocument.getTitle()).isEqualTo(latestTitle);
        assertThat(latestDocument.getDescription())
                .isEqualTo("Latest description");
        assertThat(latestDocument.getContentInputHash())
                .isNotEqualTo(oldInputHash);
        assertThat(latestDocument.getEmbedding())
                .containsExactlyElementsOf(
                        latestEmbedding.stream()
                                .map(Double::floatValue)
                                .toList()
                );
        assertThat(latestDocument.getEmbeddingInputHash())
                .isEqualTo(latestDocument.getContentInputHash());
        assertThat(latestDocument.getEmbeddingModel())
                .isEqualTo(aiProperties.embeddingModel());
        verify(embeddingClient, times(2))
                .embed(any(EmbeddingRequest.class));
    }

    @Test
    void embeddingIndexDoesNothingWhenElasticsearchDocumentIsMissing() {
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
    void backfillOnlyIndexesMissingEmbeddingsAcrossStreamingUpdates() {
        Content existingEmbeddingContent = createContent(
                "기존 Embedding 콘텐츠",
                "이미 벡터가 있는 검색 문서"
        );
        Content missingInputHashContent = createContent(
                "입력 hash 누락 Embedding 콘텐츠",
                "재생성이 필요한 기존 Embedding 문서"
        );
        Content missingModelContent = createContent(
                "모델 누락 Embedding 콘텐츠",
                "재생성이 필요한 기존 Embedding 문서"
        );
        Content mismatchedInputHashContent = createContent(
                "입력 hash 불일치 Embedding 콘텐츠",
                "재생성이 필요한 기존 Embedding 문서"
        );
        Content mismatchedModelContent = createContent(
                "모델 불일치 Embedding 콘텐츠",
                "재생성이 필요한 기존 Embedding 문서"
        );
        List<Content> contents = new ArrayList<>();
        contents.add(existingEmbeddingContent);
        contents.add(missingInputHashContent);
        contents.add(missingModelContent);
        contents.add(mismatchedInputHashContent);
        contents.add(mismatchedModelContent);
        contents.addAll(IntStream.range(0, 501)
                .mapToObj(index -> createContent(
                        "누락 Embedding 콘텐츠 " + index,
                        "Backfill 통합 테스트 콘텐츠"
                ))
                .toList());
        Content softDeletedContent = createContent(
                "Soft Delete 콘텐츠",
                "Backfill에서 건너뛸 콘텐츠"
        );
        Content mysqlMissingContent = createContent(
                "MySQL 누락 콘텐츠",
                "Backfill에서 건너뛸 Elasticsearch 문서"
        );
        contents.add(softDeletedContent);
        contents.add(mysqlMissingContent);

        contentRepository.saveAll(contents);
        contentRepository.flush();
        contentSearchIndexer.reindexAll();

        softDeletedContent.delete();
        contentRepository.saveAndFlush(softDeletedContent);
        contentRepository.deleteById(mysqlMissingContent.getId());
        contentRepository.flush();

        List<Float> existingEmbedding = IntStream.range(0, EMBEDDING_DIMENSIONS)
                .mapToObj(ignored -> 0.25F)
                .toList();
        String existingInputHash = findIndexedInputHash(existingEmbeddingContent);
        indexEmbeddingFixture(
                existingEmbeddingContent,
                existingEmbedding,
                existingInputHash,
                aiProperties.embeddingModel()
        );
        indexEmbeddingFixture(
                missingInputHashContent,
                existingEmbedding,
                null,
                aiProperties.embeddingModel()
        );
        indexEmbeddingFixture(
                missingModelContent,
                existingEmbedding,
                findIndexedInputHash(missingModelContent),
                null
        );
        indexEmbeddingFixture(
                mismatchedInputHashContent,
                existingEmbedding,
                "stale-input-hash",
                aiProperties.embeddingModel()
        );
        indexEmbeddingFixture(
                mismatchedModelContent,
                existingEmbedding,
                findIndexedInputHash(mismatchedModelContent),
                "previous-embedding-model"
        );
        elasticsearchOperations
                .indexOps(ContentSearchDocument.class)
                .refresh();

        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(createVector(1)));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(505L);
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(2L);
        verify(embeddingClient, times(505))
                .embed(any(EmbeddingRequest.class));

        ContentSearchDocument existingDocument = contentSearchRepository
                .findById(existingEmbeddingContent.getId().toString())
                .orElseThrow();
        assertThat(existingDocument.getTitle())
                .isEqualTo("기존 Embedding 콘텐츠");
        assertThat(existingDocument.getEmbedding())
                .hasSize(EMBEDDING_DIMENSIONS)
                .allMatch(value -> value.equals(0.25F));
        assertThat(existingDocument.getContentInputHash())
                .isEqualTo(existingInputHash);
        assertThat(existingDocument.getEmbeddingInputHash())
                .isEqualTo(existingInputHash);
        assertThat(existingDocument.getEmbeddingModel())
                .isEqualTo(aiProperties.embeddingModel());

        assertBackfilledEmbeddingMetadata(missingInputHashContent);
        assertBackfilledEmbeddingMetadata(missingModelContent);
        assertBackfilledEmbeddingMetadata(mismatchedInputHashContent);
        assertBackfilledEmbeddingMetadata(mismatchedModelContent);

        Content backfilledContent = contents.get(5);
        ContentSearchDocument backfilledDocument = contentSearchRepository
                .findById(backfilledContent.getId().toString())
                .orElseThrow();
        assertThat(backfilledDocument.getTitle())
                .isEqualTo(backfilledContent.getTitle());
        assertThat(backfilledDocument.getDescription())
                .isEqualTo(backfilledContent.getDescription());
        assertThat(backfilledDocument.getEmbedding())
                .hasSize(EMBEDDING_DIMENSIONS);

        assertThat(contentSearchRepository
                .findById(softDeletedContent.getId().toString())
                .orElseThrow()
                .getEmbedding()).isNull();
        assertThat(contentSearchRepository
                .findById(mysqlMissingContent.getId().toString())
                .orElseThrow()
                .getEmbedding()).isNull();

        clearInvocations(embeddingClient);
        ContentEmbeddingIndexer.BackfillResult secondResult =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(secondResult.succeeded()).isZero();
        assertThat(secondResult.failed()).isZero();
        assertThat(secondResult.skipped()).isEqualTo(2L);
        verifyNoInteractions(embeddingClient);
    }

    private String findIndexedInputHash(Content content) {
        return contentSearchRepository.findById(content.getId().toString())
                .orElseThrow()
                .getContentInputHash();
    }

    private void indexWithoutRefresh(ContentSearchDocument document) {
        Document source = elasticsearchOperations
                .getElasticsearchConverter()
                .mapObject(document);
        UpdateQuery updateQuery = UpdateQuery.builder(document.getId())
                .withDocument(source)
                .withDocAsUpsert(true)
                .withRefreshPolicy(RefreshPolicy.NONE)
                .build();

        elasticsearchOperations.update(
                updateQuery,
                elasticsearchOperations.getIndexCoordinatesFor(
                        ContentSearchDocument.class
                )
        );
    }

    private void indexEmbeddingFixture(
            Content content,
            List<Float> embedding,
            String embeddingInputHash,
            String embeddingModel
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", embedding);
        params.put("removeEmbeddingInputHash", embeddingInputHash == null);
        params.put("removeEmbeddingModel", embeddingModel == null);
        if (embeddingInputHash != null) {
            params.put("embeddingInputHash", embeddingInputHash);
        }
        if (embeddingModel != null) {
            params.put("embeddingModel", embeddingModel);
        }

        UpdateQuery updateQuery = UpdateQuery.builder(content.getId().toString())
                .withScriptType(ScriptType.INLINE)
                .withScript("""
                        ctx._source.embedding = params.embedding;
                        if (params.removeEmbeddingInputHash) {
                            ctx._source.remove('embeddingInputHash');
                        } else {
                            ctx._source.embeddingInputHash = params.embeddingInputHash;
                        }
                        if (params.removeEmbeddingModel) {
                            ctx._source.remove('embeddingModel');
                        } else {
                            ctx._source.embeddingModel = params.embeddingModel;
                        }
                        """)
                .withParams(params)
                .build();

        elasticsearchOperations.update(
                updateQuery,
                elasticsearchOperations.getIndexCoordinatesFor(
                        ContentSearchDocument.class
                )
        );
    }

    private void assertBackfilledEmbeddingMetadata(Content content) {
        ContentSearchDocument document = contentSearchRepository
                .findById(content.getId().toString())
                .orElseThrow();

        assertThat(document.getEmbedding())
                .hasSize(EMBEDDING_DIMENSIONS)
                .containsExactlyElementsOf(
                        createVector(1).stream()
                                .map(Double::floatValue)
                                .toList()
                );
        assertThat(document.getEmbeddingInputHash())
                .isEqualTo(document.getContentInputHash())
                .isNotBlank();
        assertThat(document.getEmbeddingModel())
                .isEqualTo(aiProperties.embeddingModel());
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
