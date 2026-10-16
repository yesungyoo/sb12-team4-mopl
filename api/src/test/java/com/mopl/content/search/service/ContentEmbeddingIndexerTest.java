package com.mopl.content.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.config.AiProperties;
import com.mopl.infrastructure.ai.exception.AiClientException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.ResourceNotFoundException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.MultiGetItem;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ContentEmbeddingIndexerTest {

    private static final String CURRENT_INPUT_HASH =
            "current-input-hash";
    private static final String CURRENT_EMBEDDING_MODEL =
            "text-embedding-3-small";
    private static final UpdateResponse UPDATED_RESPONSE =
            new UpdateResponse(UpdateResponse.Result.UPDATED);

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentTagRepository contentTagRepository;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private IndexOperations indexOperations;

    @Mock
    private ContentEmbeddingService contentEmbeddingService;

    @Mock
    private AiProperties aiProperties;

    @Mock
    private Content content;

    @Mock
    private ContentTag contentTag;

    @Mock
    private ContentSearchDocument document;

    @Mock
    private ContentSearchDocument backfillDocument;

    @Mock
    private SearchHit<ContentSearchDocument> backfillDocumentHit;

    private ContentEmbeddingIndexer contentEmbeddingIndexer;

    @BeforeEach
    void setUp() {
        contentEmbeddingIndexer =
                createIndexer(true);
        lenient().when(elasticsearchOperations.update(
                any(UpdateQuery.class),
                any()
        )).thenReturn(UPDATED_RESPONSE);
        lenient().when(aiProperties.embeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        lenient().when(contentEmbeddingService.calculateInputHash(
                any(Content.class),
                anyList()
        )).thenReturn(CURRENT_INPUT_HASH);
        lenient().when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        lenient().when(backfillDocument.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        lenient().when(backfillDocument.getEmbeddingInputHash())
                .thenReturn("outdated-input-hash");
        lenient().when(backfillDocument.getEmbeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        lenient().when(backfillDocumentHit.getContent())
                .thenReturn(backfillDocument);
        lenient().when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);
        lenient().when(elasticsearchOperations.searchOne(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(backfillDocumentHit);
        lenient().when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(List.of(
                MultiGetItem.of(backfillDocument, null)
        ));
    }

    @Test
    void doesNothingWhenAiIsDisabled() {
        ContentEmbeddingIndexer disabledIndexer =
                createIndexer(false);

        disabledIndexer.index(content);

        long indexedCount =
                disabledIndexer.reindexAll();

        assertThat(indexedCount).isZero();

        verifyNoInteractions(
                contentRepository,
                contentTagRepository,
                elasticsearchOperations,
                contentEmbeddingService
        );
    }

    @Test
    void doesNothingWhenApiKeyIsMissing() {
        when(aiProperties.apiKey())
                .thenReturn(" ");

        contentEmbeddingIndexer.index(content);

        long indexedCount =
                contentEmbeddingIndexer.reindexAll();

        assertThat(indexedCount).isZero();

        verifyNoInteractions(
                contentRepository,
                contentTagRepository,
                elasticsearchOperations,
                contentEmbeddingService
        );
    }

    @Test
    void skipsRecalculationWhenEmbeddingMetadataIsCurrent() {
        List<ContentTag> contentTags =
                prepareExistingDocumentForIndex();

        when(document.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        when(aiProperties.embeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(content, contentTags);

        ArgumentCaptor<Query> metadataQueryCaptor =
                ArgumentCaptor.forClass(Query.class);
        verify(elasticsearchOperations).multiGet(
                metadataQueryCaptor.capture(),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        );

        ArgumentCaptor<NativeQuery> existsQueryCaptor =
                ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).searchOne(
                existsQueryCaptor.capture(),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        );

        assertThat(metadataQueryCaptor.getValue()
                .getSourceFilter().getIncludes())
                .containsExactly(
                        "id",
                        "contentInputHash",
                        "embeddingInputHash",
                        "embeddingModel"
                );
        assertThat(metadataQueryCaptor.getValue()
                .getSourceFilter().getExcludes())
                .isEmpty();
        assertThat(metadataQueryCaptor.getValue().getIds())
                .containsExactly(content.getId().toString());
        assertThat(existsQueryCaptor.getValue()
                .getSourceFilter().getIncludes())
                .containsExactly("id");
        assertThat(existsQueryCaptor.getValue()
                .getSourceFilter().getExcludes())
                .isEmpty();
    }

    @Test
    void recalculatesWhenEmbeddingInputHashChanged() {
        List<ContentTag> contentTags =
                prepareExistingDocumentForIndex();

        when(document.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingInputHash())
                .thenReturn("outdated-input-hash");
        when(aiProperties.embeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        stubSuccessfulEmbedding(contentTags);

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService)
                .embedContentWithMetadata(content, contentTags);
    }

    @Test
    void recalculatesWhenEmbeddingModelChanged() {
        List<ContentTag> contentTags =
                prepareExistingDocumentForIndex();

        when(document.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingModel())
                .thenReturn("outdated-embedding-model");
        when(aiProperties.embeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        stubSuccessfulEmbedding(contentTags);

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService)
                .embedContentWithMetadata(content, contentTags);
    }

    @Test
    void recalculatesWhenEmbeddingMetadataIsMissing() {
        List<ContentTag> contentTags =
                prepareExistingDocumentForIndex();

        when(document.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(aiProperties.embeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        stubSuccessfulEmbedding(contentTags);

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService)
                .embedContentWithMetadata(content, contentTags);
    }

    @Test
    void recalculatesWhenEmbeddingIsMissing() {
        List<ContentTag> contentTags =
                prepareIndexInput();
        stubMissingEmbeddingLookup();

        when(document.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(document.getEmbeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        when(aiProperties.embeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        stubSuccessfulEmbedding(contentTags);

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService)
                .embedContentWithMetadata(content, contentTags);
        verify(elasticsearchOperations).multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        );
        verify(elasticsearchOperations).searchOne(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        );
    }

    @Test
    void skipsRecalculationWhenContentInputHashIsMissing() {
        List<ContentTag> contentTags =
                prepareExistingDocumentForIndex();

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(content, contentTags);
        verify(aiProperties, never()).embeddingModel();
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any(IndexCoordinates.class));
    }

    @Test
    void skipsRecalculationWhenContentInputHashDoesNotMatchDatabaseInput() {
        List<ContentTag> contentTags =
                prepareExistingDocumentForIndex();

        when(document.getContentInputHash())
                .thenReturn("outdated-content-input-hash");

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(content, contentTags);
        verify(aiProperties, never()).embeddingModel();
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any(IndexCoordinates.class));
    }

    @Test
    void keepsExistingBehaviorWhenElasticsearchDocumentDoesNotExist() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        List<ContentTag> contentTags = List.of(contentTag);

        when(content.getId()).thenReturn(contentId);
        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);
        when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(List.of(
                MultiGetItem.<ContentSearchDocument>of(null, null)
        ));

        contentEmbeddingIndexer.index(content);

        verify(contentEmbeddingService, never())
                .calculateInputHash(any(Content.class), anyList());
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any(IndexCoordinates.class));
    }

    @Test
    void updatesEmbeddingAndMetadataAtomicallyForExistingDocument() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");
        List<ContentTag> contentTags =
                List.of(contentTag);
        List<Double> embedding =
                List.of(0.1D, 0.2D);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        stubExistingDocumentLookup();

        stubMatchingContentInputHash(contentTags);

        when(contentEmbeddingService.embedContentWithMetadata(
                content,
                contentTags
        )).thenReturn(embeddingGeneration(embedding));

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        contentEmbeddingIndexer.index(content);

        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);

        InOrder indexingOrder =
                inOrder(
                        contentTagRepository,
                        contentEmbeddingService,
                        elasticsearchOperations
                );

        indexingOrder.verify(contentTagRepository)
                .findAllByContentId(contentId);

        indexingOrder.verify(elasticsearchOperations)
                .multiGet(
                        any(Query.class),
                        eq(ContentSearchDocument.class),
                        same(indexCoordinates)
                );

        indexingOrder.verify(contentEmbeddingService)
                .calculateInputHash(content, contentTags);

        indexingOrder.verify(contentEmbeddingService)
                .embedContentWithMetadata(content, contentTags);

        indexingOrder.verify(elasticsearchOperations)
                .getIndexCoordinatesFor(
                        ContentSearchDocument.class
                );

        indexingOrder.verify(elasticsearchOperations)
                .update(
                        updateQueryCaptor.capture(),
                        same(indexCoordinates)
                );

        UpdateQuery updateQuery =
                updateQueryCaptor.getValue();

        assertThat(updateQuery.getId())
                .isEqualTo(contentId.toString());

        assertThat(updateQuery.getDocument()).isNull();
        assertThat(updateQuery.getScriptType())
                .isEqualTo(ScriptType.INLINE);
        assertThat(updateQuery.getScript())
                .contains("ctx._source.containsKey('contentInputHash')")
                .contains("ctx._source.contentInputHash == params.embeddingInputHash")
                .contains("ctx._source.embedding = params.embedding")
                .contains("ctx._source.embeddingInputHash = params.embeddingInputHash")
                .contains("ctx._source.embeddingModel = params.embeddingModel")
                .contains("ctx.op = 'noop'");
        assertThat(updateQuery.getParams())
                .containsOnly(
                        Map.entry("embedding", List.of(0.1F, 0.2F)),
                        Map.entry("embeddingInputHash", CURRENT_INPUT_HASH),
                        Map.entry("embeddingModel", CURRENT_EMBEDDING_MODEL)
                );

        assertThat(updateQuery.getDocAsUpsert())
                .isNotEqualTo(Boolean.TRUE);

        assertThat(updateQuery.getUpsert())
                .isNull();

        assertThat(updateQuery.getRetryOnConflict())
                .isEqualTo(3);

        assertThat(updateQuery.getRefreshPolicy())
                .isEqualTo(RefreshPolicy.IMMEDIATE);
    }

    @Test
    void doesNotUpdateWhenEmbeddingIsNull() {
        assertMissingEmbeddingIsNotUpdated(null);
    }

    @Test
    void doesNotUpdateWhenEmbeddingIsEmpty() {
        assertMissingEmbeddingIsNotUpdated(List.of());
    }

    @Test
    void doesNotPropagateEmbeddingGenerationFailure() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        List<ContentTag> contentTags =
                List.of(contentTag);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        stubExistingDocumentLookup();

        stubMatchingContentInputHash(contentTags);

        when(contentEmbeddingService.embedContentWithMetadata(
                content,
                contentTags
        )).thenThrow(
                new RuntimeException("OpenAI unavailable")
        );

        assertThatCode(
                () -> contentEmbeddingIndexer.index(content)
        ).doesNotThrowAnyException();

        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any(IndexCoordinates.class));
    }

    @Test
    void doesNotPropagateEmbeddingUpdateFailure() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");
        List<ContentTag> contentTags =
                List.of(contentTag);
        List<Double> embedding =
                List.of(0.1D, 0.2D);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        stubExistingDocumentLookup();

        stubMatchingContentInputHash(contentTags);

        when(contentEmbeddingService.embedContentWithMetadata(
                content,
                contentTags
        )).thenReturn(embeddingGeneration(embedding));

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        when(elasticsearchOperations.update(
                any(UpdateQuery.class),
                same(indexCoordinates)
        ))
                .thenThrow(
                        new RuntimeException(
                                "Elasticsearch unavailable"
                        )
                );

        assertThatCode(
                () -> contentEmbeddingIndexer.index(content)
        ).doesNotThrowAnyException();

        verify(elasticsearchOperations)
                .update(
                        any(UpdateQuery.class),
                        same(indexCoordinates)
                );

    }

    @Test
    void backfillMissingQueriesOnlyMissingEmbeddingsAndCountsOutcomes() {
        enableAi();

        Content firstContent = mock(Content.class);
        Content secondContent = mock(Content.class);
        Content thirdContent = mock(Content.class);
        UUID firstContentId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();
        UUID thirdContentId = UUID.randomUUID();
        UUID deletedContentId = UUID.randomUUID();
        IndexCoordinates indexCoordinates =
                IndexCoordinates.of("contents");
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(List.of(
                        firstContentId,
                        secondContentId,
                        thirdContentId,
                        deletedContentId
                ));

        when(firstContent.getId())
                .thenReturn(firstContentId);
        when(secondContent.getId())
                .thenReturn(secondContentId);
        when(thirdContent.getId())
                .thenReturn(thirdContentId);
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(
                List.of(
                        firstContentId,
                        secondContentId,
                        thirdContentId,
                        deletedContentId
                )
        )).thenReturn(List.of(firstContent, secondContent, thirdContent));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());

        when(contentEmbeddingService.embedContentWithMetadata(
                firstContent,
                List.of()
        )).thenReturn(embeddingGeneration(List.of(0.1D, 0.2D)));

        when(contentEmbeddingService.embedContentWithMetadata(
                secondContent,
                List.of()
        )).thenThrow(
                new RuntimeException("OpenAI unavailable")
        );

        when(contentEmbeddingService.embedContentWithMetadata(
                thirdContent,
                List.of()
        )).thenReturn(embeddingGeneration(List.of(0.3D, 0.4D)));

        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(indexCoordinates);

        when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(2L);
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isEqualTo(1L);

        ArgumentCaptor<NativeQuery> queryCaptor =
                ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).searchForStream(
                queryCaptor.capture(),
                eq(ContentSearchDocument.class)
        );
        assertThat(queryCaptor.getValue().getQuery().matchAll())
                .isNotNull();
        assertThat(queryCaptor.getValue().getSourceFilter().getIncludes())
                .containsExactly(
                        "id",
                        "contentInputHash",
                        "embeddingInputHash",
                        "embeddingModel"
                );
        assertThat(queryCaptor.getValue().getSourceFilter().getExcludes())
                .isEmpty();
        assertThat(queryCaptor.getValue().getPageable().getPageSize())
                .isEqualTo(500);

        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);

        verify(elasticsearchOperations, times(2))
                .update(
                        updateQueryCaptor.capture(),
                        same(indexCoordinates)
                );

        assertThat(updateQueryCaptor.getAllValues())
                .extracting(UpdateQuery::getId)
                .containsExactlyInAnyOrder(
                        firstContentId.toString(),
                        thirdContentId.toString()
                );
        assertThat(updateQueryCaptor.getAllValues())
                .allSatisfy(updateQuery -> {
                    assertThat(updateQuery.getDocument()).isNull();
                    assertThat(updateQuery.getScriptType())
                            .isEqualTo(ScriptType.INLINE);
                    assertThat(updateQuery.getParams())
                            .containsKeys(
                                    "embedding",
                                    "embeddingInputHash",
                                    "embeddingModel"
                            );
                    assertThat(updateQuery.getRefreshPolicy())
                            .isEqualTo(RefreshPolicy.NONE);
                });

        verify(contentEmbeddingService, times(3))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(anyList());
        verify(searchHits).close();
        InOrder backfillOrder = inOrder(searchHits, contentEmbeddingService);
        backfillOrder.verify(searchHits).close();
        backfillOrder.verify(contentEmbeddingService)
                .embedContentWithMetadata(firstContent, List.of());

        verify(indexOperations)
                .refresh();
    }

    @Test
    void backfillSkipsContentWithoutContentInputHash() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(backfillDocument.getContentInputHash()).thenReturn(null);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1L);
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillSkipsWhenDatabaseInputDiffersFromContentInputHash() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(contentEmbeddingService.calculateInputHash(
                any(Content.class),
                anyList()
        )).thenReturn("different-database-input-hash");

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1L);
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillRecalculatesWhenEmbeddingInputHashIsMissing() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(backfillDocument.getEmbeddingInputHash()).thenReturn(null);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(1L);
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isZero();
        verify(contentEmbeddingService)
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillRecalculatesWhenEmbeddingModelIsMissing() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(backfillDocument.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(backfillDocument.getEmbeddingModel()).thenReturn(null);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(1L);
        assertThat(result.failed()).isZero();
        verify(contentEmbeddingService)
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillRecalculatesWhenEmbeddingModelIsOutdated() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(backfillDocument.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(backfillDocument.getEmbeddingModel())
                .thenReturn("old-embedding-model");
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(1L);
        assertThat(result.failed()).isZero();
        verify(contentEmbeddingService)
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillSkipsWhenEmbeddingBecameCurrentBeforeProcessing() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        BackfillFixture fixture = createBackfillFixture(List.of(contentId));
        ContentSearchDocument currentDocument =
                mock(ContentSearchDocument.class);
        when(currentDocument.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(currentDocument.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(currentDocument.getEmbeddingModel())
                .thenReturn(CURRENT_EMBEDDING_MODEL);
        when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(List.of(MultiGetItem.of(currentDocument, null)));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1L);
        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(List.of(contentId));
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillFindsMissingEmbeddingWithoutFetchingVectorSource() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        SearchHitsIterator<ContentSearchDocument> missingEmbeddingHits =
                createSearchHitsIterator(List.of(contentId));
        BackfillFixture fixture = createBackfillFixture(
                List.of(contentId),
                missingEmbeddingHits
        );
        when(backfillDocument.getEmbeddingInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
        when(elasticsearchOperations.searchOne(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(null);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(1L);
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isZero();
        verify(elasticsearchOperations, times(2))
                .searchForStream(
                        any(NativeQuery.class),
                        eq(ContentSearchDocument.class)
                );
        verify(contentEmbeddingService)
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
        verify(missingEmbeddingHits).close();
    }

    @Test
    void backfillSkipsWhenElasticsearchDocumentWasDeleted() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(List.of(
                MultiGetItem.<ContentSearchDocument>of(null, null)
        ));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1L);
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillCountsLatestMetadataReadFailureAsElasticsearchFailure() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenThrow(new RuntimeException("Elasticsearch unavailable"));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isZero();
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason.NONE);
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillStopsAfterConsecutiveMissingIndexMetadataReads() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveElasticsearchFailures",
                2
        );
        BackfillFixture fixture = createBackfillFixture(4);
        when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenThrow(new ResourceNotFoundException("index missing"));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(2L);
        assertThat(result.skipped()).isZero();
        assertThat(result.unprocessed()).isEqualTo(2L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_ELASTICSEARCH_FAILURE_LIMIT);
        verify(elasticsearchOperations, times(2)).multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        );
        verify(contentEmbeddingService, never())
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillRecoversFailedEmbeddingOnTheNextRun() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        BackfillFixture firstFixture = createBackfillFixture(List.of(contentId));
        SearchHitsIterator<ContentSearchDocument> secondRunHits =
                createSearchHitsIterator(List.of(contentId));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(firstFixture.searchHits(), secondRunHits);
        AtomicInteger calls = new AtomicInteger();
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("OpenAI unavailable");
            }
            return embeddingGeneration(List.of(0.1D));
        });

        ContentEmbeddingIndexer.BackfillResult firstResult =
                contentEmbeddingIndexer.backfillMissing();
        ContentEmbeddingIndexer.BackfillResult secondResult =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(firstResult.failed()).isEqualTo(1L);
        assertThat(secondResult.succeeded()).isEqualTo(1L);
        assertThat(calls).hasValue(2);
        verify(firstFixture.searchHits()).close();
        verify(secondRunHits).close();
    }

    @Test
    void backfillMissingProcessesMoreThanOneSearchBatchWithoutRepeatingHits() {
        enableAi();

        List<UUID> contentIds = IntStream.range(0, 501)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        Map<UUID, Content> contentsById = new HashMap<>();
        contentIds.forEach(contentId -> {
            Content content = mock(Content.class);
            when(content.getId()).thenReturn(contentId);
            contentsById.put(contentId, content);
        });
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(contentIds);

        lenient().when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        List<List<UUID>> queriedBatches = new ArrayList<>();
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> batchIds = new ArrayList<>(
                            invocation.getArgument(0)
                    );
                    queriedBatches.add(batchIds);
                    return batchIds.stream()
                            .map(contentsById::get)
                            .toList();
                });
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.indexOps(
                ContentSearchDocument.class
        )).thenReturn(indexOperations);

        assertThat(contentEmbeddingIndexer.reindexAll())
                .isEqualTo(501L);

        verify(contentRepository, times(2))
                .findAllByIdInAndDeletedAtIsNull(anyList());
        assertThat(queriedBatches)
                .extracting(List::size)
                .containsExactly(500, 1);
        assertThat(queriedBatches.stream()
                .flatMap(List::stream)
                .toList())
                .containsExactlyElementsOf(contentIds);
        verify(contentEmbeddingService, times(501))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, times(501))
                .update(any(UpdateQuery.class), any());
        verify(searchHits).close();
    }

    @Test
    void backfillMissingStopsImmediatelyOn401AuthenticationFailure() {
        assertBackfillStopsOnAuthenticationFailure(401);
    }

    @Test
    void backfillMissingStopsImmediatelyOn403AuthenticationFailure() {
        assertBackfillStopsOnAuthenticationFailure(403);
    }

    @Test
    void backfillMissingStopsAfterFiveConsecutiveFailures() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(7);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenThrow(new RuntimeException("OpenAI unavailable"));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(5L);
        assertThat(result.skipped()).isZero();
        assertThat(result.unprocessed()).isEqualTo(2L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_FAILURE_LIMIT);
        verify(contentEmbeddingService, times(5))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingResetsConsecutiveFailuresAfterSuccess() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(9);
        AtomicInteger calls = new AtomicInteger();
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenAnswer(invocation -> {
                    int call = calls.incrementAndGet();
                    if (call == 3) {
                        return embeddingGeneration(List.of(0.1D));
                    }
                    throw new RuntimeException("OpenAI unavailable");
                });
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(1L);
        assertThat(result.failed()).isEqualTo(7L);
        assertThat(result.skipped()).isZero();
        assertThat(result.unprocessed()).isEqualTo(1L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_FAILURE_LIMIT);
        verify(contentEmbeddingService, times(8))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations).update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingUsesConfiguredConsecutiveFailureThreshold() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveFailures",
                2
        );
        BackfillFixture fixture = createBackfillFixture(4);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenThrow(new RuntimeException("OpenAI unavailable"));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.failed()).isEqualTo(2L);
        assertThat(result.unprocessed()).isEqualTo(2L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_FAILURE_LIMIT);
        verify(contentEmbeddingService, times(2))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingDoesNotCountElasticsearchUpdateFailuresAsOpenAiFailures() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveFailures",
                2
        );
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveElasticsearchFailures",
                7
        );
        BackfillFixture fixture = createBackfillFixture(6);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new RuntimeException("Elasticsearch update unavailable"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(6L);
        assertThat(result.unprocessed()).isZero();
        assertThat(result.stoppedEarly()).isFalse();
        verify(contentEmbeddingService, times(6))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, times(6))
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingStopsAfterFiveConsecutiveElasticsearchUpdateFailures() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(7);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new RuntimeException("Elasticsearch update unavailable"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(5L);
        assertThat(result.skipped()).isZero();
        assertThat(result.unprocessed()).isEqualTo(2L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_ELASTICSEARCH_FAILURE_LIMIT);
        verify(contentEmbeddingService, times(5))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, times(5))
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingResetsElasticsearchFailureCountAfterSuccessfulSave() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveElasticsearchFailures",
                3
        );
        BackfillFixture fixture = createBackfillFixture(6);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        AtomicInteger updateCalls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = updateCalls.incrementAndGet();
            if (call != 3 && call != 6) {
                throw new RuntimeException("Elasticsearch update unavailable");
            }
            return UPDATED_RESPONSE;
        }).when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(2L);
        assertThat(result.failed()).isEqualTo(4L);
        assertThat(result.unprocessed()).isZero();
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason.NONE);
        verify(contentEmbeddingService, times(6))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, times(6))
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingTreatsNoopAsSkippedAndResetsElasticsearchFailures() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveElasticsearchFailures",
                2
        );
        BackfillFixture fixture = createBackfillFixture(3);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        AtomicInteger updateCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (updateCalls.incrementAndGet() == 2) {
                return new UpdateResponse(UpdateResponse.Result.NOOP);
            }
            throw new RuntimeException("Elasticsearch update unavailable");
        }).when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(2L);
        assertThat(result.skipped()).isEqualTo(1L);
        assertThat(result.unprocessed()).isZero();
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason.NONE);
        verify(elasticsearchOperations, times(3))
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingTreatsNotFoundAsSkippedWithoutRecreatingDocument() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.update(
                any(UpdateQuery.class),
                any()
        )).thenReturn(new UpdateResponse(UpdateResponse.Result.NOT_FOUND));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1L);
        ArgumentCaptor<UpdateQuery> updateQueryCaptor =
                ArgumentCaptor.forClass(UpdateQuery.class);
        verify(elasticsearchOperations).update(
                updateQueryCaptor.capture(),
                any()
        );
        assertThat(updateQueryCaptor.getValue().getDocAsUpsert())
                .isNotEqualTo(Boolean.TRUE);
        assertThat(updateQueryCaptor.getValue().getUpsert()).isNull();
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingTreatsDocumentMissingExceptionAsSkipped() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new ResourceNotFoundException("document missing"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1L);
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingDoesNotTreatUnexpectedUpdateResultAsSuccess() {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(1);
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        when(elasticsearchOperations.update(
                any(UpdateQuery.class),
                any()
        )).thenReturn(new UpdateResponse(UpdateResponse.Result.CREATED));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isZero();
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingUsesConfiguredElasticsearchFailureThreshold() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveElasticsearchFailures",
                2
        );
        BackfillFixture fixture = createBackfillFixture(4);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new RuntimeException("Elasticsearch update unavailable"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.failed()).isEqualTo(2L);
        assertThat(result.unprocessed()).isEqualTo(2L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_ELASTICSEARCH_FAILURE_LIMIT);
        verify(contentEmbeddingService, times(2))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, times(2))
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @Test
    void backfillMissingResetsOpenAiFailureCountWhenEmbeddingSucceedsButEsUpdateFails() {
        enableAi();
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveFailures",
                2
        );
        BackfillFixture fixture = createBackfillFixture(5);
        AtomicInteger embeddingCalls = new AtomicInteger();
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenAnswer(invocation -> {
                    int call = embeddingCalls.incrementAndGet();
                    if (call == 2) {
                        return embeddingGeneration(List.of(0.1D));
                    }
                    throw new RuntimeException("OpenAI unavailable");
                });
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new RuntimeException("Elasticsearch update unavailable"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.failed()).isEqualTo(4L);
        assertThat(result.unprocessed()).isEqualTo(1L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .CONSECUTIVE_FAILURE_LIMIT);
        verify(contentEmbeddingService, times(4))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations).update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveConsecutiveFailureThreshold(int threshold) {
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveFailures",
                threshold
        );

        assertThatThrownBy(contentEmbeddingIndexer::validateBackfillConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "mopl.elasticsearch.embedding-backfill.max-consecutive-failures"
                )
                .hasMessageContaining("양수");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveElasticsearchFailureThreshold(int threshold) {
        ReflectionTestUtils.setField(
                contentEmbeddingIndexer,
                "maxConsecutiveElasticsearchFailures",
                threshold
        );

        assertThatThrownBy(contentEmbeddingIndexer::validateBackfillConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "mopl.elasticsearch.embedding-backfill.max-consecutive-es-failures"
                )
                .hasMessageContaining("양수");
    }

    @Test
    void backfillMissingWrapsSecondBatchDatabaseFailureAfterFirstBatchCompletes() {
        enableAi();

        List<UUID> contentIds = IntStream.range(0, 501)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        Map<UUID, Content> contentsById = new HashMap<>();
        contentIds.stream()
                .limit(500)
                .forEach(contentId -> {
                    Content content = mock(Content.class);
                    when(content.getId()).thenReturn(contentId);
                    contentsById.put(contentId, content);
                });
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(contentIds);
        RuntimeException databaseFailure =
                new RuntimeException("MySQL unavailable");
        AtomicInteger repositoryCalls = new AtomicInteger();

        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenAnswer(invocation -> {
                    if (repositoryCalls.incrementAndGet() == 2) {
                        throw databaseFailure;
                    }

                    List<UUID> batchIds = invocation.getArgument(0);
                    return batchIds.stream()
                            .map(contentsById::get)
                            .toList();
                });
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContentWithMetadata(
                any(Content.class),
                anyList()
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));

        assertThatThrownBy(contentEmbeddingIndexer::backfillMissing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("단계=DB 배치 처리")
                .hasMessageContaining("성공=500")
                .hasCause(databaseFailure);

        verify(contentRepository, times(2))
                .findAllByIdInAndDeletedAtIsNull(anyList());
        verify(contentEmbeddingService, times(500))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, times(500))
                .update(any(UpdateQuery.class), any());
        verify(searchHits).close();
        verify(searchHits, times(501)).next();
    }

    @Test
    void backfillMissingPropagatesStreamFailureWithoutEmbeddingProcessing() {
        enableAi();

        SearchHitsIterator<ContentSearchDocument> searchHits =
                mock(SearchHitsIterator.class);
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(searchHits.hasNext())
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));

        assertThatThrownBy(contentEmbeddingIndexer::backfillMissing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("단계=ES 스트림 조회");

        verify(searchHits).close();
        verify(indexOperations, never()).refresh();
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any());
        verifyNoInteractions(contentRepository, contentTagRepository,
                contentEmbeddingService);
    }

    @Test
    void backfillMissingSkipsInvalidAndDuplicateDocumentIds() {
        enableAi();

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Content first = mock(Content.class);
        Content second = mock(Content.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIteratorFromIds(List.of(
                        firstId.toString(),
                        "not-a-uuid",
                        firstId.toString(),
                        secondId.toString()
                ));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(first, second));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isEqualTo(2L);
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(2L);
        ArgumentCaptor<List<UUID>> idsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(contentRepository)
                .findAllByIdInAndDeletedAtIsNull(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(firstId, secondId);
        verify(contentEmbeddingService, times(2))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(searchHits).close();
    }

    @Test
    void backfillMissingCountsElasticsearchUpdateFailure() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(List.of(contentId));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(content));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContentWithMetadata(content, List.of()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.getIndexCoordinatesFor(
                ContentSearchDocument.class
        )).thenReturn(IndexCoordinates.of("contents"));
        doThrow(new RuntimeException("Elasticsearch update unavailable"))
                .when(elasticsearchOperations)
                .update(any(UpdateQuery.class), any());
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isZero();
        verify(searchHits).close();
    }

    @Test
    void backfillMissingReportsRefreshFailureAfterSuccessfulUpdates() {
        enableAi();
        UUID contentId = UUID.randomUUID();
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(List.of(contentId));
        when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(content));
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(contentEmbeddingService.embedContentWithMetadata(content, List.of()))
                .thenReturn(embeddingGeneration(List.of(0.1D)));
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);
        doThrow(new RuntimeException("refresh unavailable"))
                .when(indexOperations).refresh();

        assertThatThrownBy(contentEmbeddingIndexer::backfillMissing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("단계=ES Refresh")
                .satisfies(exception -> assertThat(exception.getCause())
                        .hasMessage("refresh unavailable"));

        verify(elasticsearchOperations).update(any(UpdateQuery.class), any());
        verify(searchHits).close();
    }

    private void assertBackfillStopsOnAuthenticationFailure(int statusCode) {
        enableAi();
        BackfillFixture fixture = createBackfillFixture(4);
        when(contentEmbeddingService.embedContentWithMetadata(any(Content.class), anyList()))
                .thenThrow(new AiClientException(
                        "OpenAI 인증 실패",
                        statusCode,
                        new RuntimeException("HTTP " + statusCode)
                ));

        ContentEmbeddingIndexer.BackfillResult result =
                contentEmbeddingIndexer.backfillMissing();

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEqualTo(1L);
        assertThat(result.skipped()).isZero();
        assertThat(result.unprocessed()).isEqualTo(3L);
        assertThat(result.stopReason())
                .isEqualTo(ContentEmbeddingIndexer.BackfillStopReason
                        .AUTHENTICATION_ERROR);
        verify(contentEmbeddingService, times(1))
                .embedContentWithMetadata(any(Content.class), anyList());
        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any());
        verify(fixture.searchHits()).close();
    }

    private BackfillFixture createBackfillFixture(int count) {
        List<UUID> contentIds = IntStream.range(0, count)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        return createBackfillFixture(contentIds);
    }

    @SafeVarargs
    private final BackfillFixture createBackfillFixture(
            List<UUID> contentIds,
            SearchHitsIterator<ContentSearchDocument>... additionalSearchHits
    ) {
        Map<UUID, Content> contentsById = new HashMap<>();
        contentIds.forEach(contentId -> {
            Content indexedContent = new Content(
                    ContentType.MOVIE,
                    "Backfill test",
                    null,
                    null,
                    ExternalSource.MANUAL,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            ReflectionTestUtils.setField(indexedContent, "id", contentId);
            contentsById.put(contentId, indexedContent);
        });

        SearchHitsIterator<ContentSearchDocument> searchHits =
                createSearchHitsIterator(contentIds);
        lenient().when(elasticsearchOperations.searchForStream(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class)
        )).thenReturn(searchHits, additionalSearchHits);
        when(contentRepository.findAllByIdInAndDeletedAtIsNull(anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> batchIds = invocation.getArgument(0);
                    return batchIds.stream()
                            .map(contentsById::get)
                            .toList();
                });
        when(contentTagRepository.findAllByContentIds(anyCollection()))
                .thenReturn(List.of());
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        return new BackfillFixture(searchHits);
    }

    private record BackfillFixture(
            SearchHitsIterator<ContentSearchDocument> searchHits
    ) {
    }

    private List<ContentTag> prepareExistingDocumentForIndex() {
        List<ContentTag> contentTags = prepareIndexInput();
        stubExistingDocumentLookup();
        return contentTags;
    }

    private List<ContentTag> prepareIndexInput() {
        enableAi();

        UUID contentId = UUID.randomUUID();
        List<ContentTag> contentTags = List.of(contentTag);

        when(content.getId()).thenReturn(contentId);
        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);
        when(contentEmbeddingService.calculateInputHash(
                content,
                contentTags
        )).thenReturn(CURRENT_INPUT_HASH);

        return contentTags;
    }

    private void stubSuccessfulEmbedding(
            List<ContentTag> contentTags
    ) {
        when(contentEmbeddingService.embedContentWithMetadata(
                content,
                contentTags
        )).thenReturn(embeddingGeneration(List.of(0.1D)));
    }

    private ContentEmbeddingService.EmbeddingGeneration embeddingGeneration(
            List<Double> embedding
    ) {
        return new ContentEmbeddingService.EmbeddingGeneration(
                embedding,
                CURRENT_INPUT_HASH,
                CURRENT_EMBEDDING_MODEL
        );
    }

    private ContentEmbeddingIndexer createIndexer(
            boolean aiEnabled
    ) {
        return new ContentEmbeddingIndexer(
                contentRepository,
                contentTagRepository,
                elasticsearchOperations,
                contentEmbeddingService,
                aiProperties,
                new AiAvailability(
                        aiProperties,
                        aiEnabled
                )
        );
    }

    private void enableAi() {
        when(aiProperties.apiKey())
                .thenReturn("test-api-key");
    }

    private SearchHitsIterator<ContentSearchDocument> createSearchHitsIterator(
            List<UUID> contentIds
    ) {
        return createSearchHitsIteratorFromIds(
                contentIds.stream().map(UUID::toString).toList()
        );
    }

    private SearchHitsIterator<ContentSearchDocument>
    createSearchHitsIteratorFromIds(
            List<String> documentIds
    ) {
        SearchHitsIterator<ContentSearchDocument> searchHits =
                mock(SearchHitsIterator.class);
        List<SearchHit<ContentSearchDocument>> hits = documentIds.stream()
                .map(documentId -> {
                    SearchHit<ContentSearchDocument> hit =
                            mock(SearchHit.class);
                    lenient().when(hit.getId()).thenReturn(documentId);
                    lenient().when(hit.getContent()).thenReturn(backfillDocument);
                    return hit;
                })
                .toList();
        AtomicInteger position = new AtomicInteger();

        lenient().when(searchHits.hasNext())
                .thenAnswer(invocation -> position.get() < hits.size());
        lenient().when(searchHits.next())
                .thenAnswer(invocation -> hits.get(position.getAndIncrement()));

        return searchHits;
    }

    private void assertMissingEmbeddingIsNotUpdated(
            List<Double> embedding
    ) {
        enableAi();

        UUID contentId = UUID.randomUUID();
        List<ContentTag> contentTags =
                List.of(contentTag);

        when(content.getId())
                .thenReturn(contentId);

        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(contentTags);

        stubExistingDocumentLookup();

        stubMatchingContentInputHash(contentTags);

        when(contentEmbeddingService.embedContentWithMetadata(
                content,
                contentTags
        )).thenReturn(embeddingGeneration(embedding));

        contentEmbeddingIndexer.index(content);

        verify(elasticsearchOperations, never())
                .update(any(UpdateQuery.class), any(IndexCoordinates.class));
    }

    private void stubExistingDocumentLookup() {
        when(elasticsearchOperations.multiGet(
                any(Query.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(List.of(MultiGetItem.of(document, null)));
    }

    private void stubMissingEmbeddingLookup() {
        stubExistingDocumentLookup();
        when(elasticsearchOperations.searchOne(
                any(NativeQuery.class),
                eq(ContentSearchDocument.class),
                any(IndexCoordinates.class)
        )).thenReturn(null);
    }

    private void stubMatchingContentInputHash(
            List<ContentTag> contentTags
    ) {
        when(contentEmbeddingService.calculateInputHash(
                content,
                contentTags
        )).thenReturn(CURRENT_INPUT_HASH);
        when(document.getContentInputHash())
                .thenReturn(CURRENT_INPUT_HASH);
    }
}
