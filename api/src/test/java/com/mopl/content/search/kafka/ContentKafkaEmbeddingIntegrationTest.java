package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import com.mopl.infrastructure.ai.exception.AiClientException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.kafka.consumer.group-id=content-search-embedding-it",
        "mopl.kafka.embedding.group-id=content-embedding-it",
        "mopl.kafka.consumer.retry-interval-ms=100",
        "mopl.kafka.consumer.retry-max-retries=2",
        "mopl.kafka.dlt.producer.request-timeout-ms=100",
        "mopl.kafka.dlt.producer.delivery-timeout-ms=200",
        "mopl.kafka.dlt.producer.ack-timeout-ms=300",
        "mopl.ai.enabled=true",
        "ai.openai.api-key=test-key",
        "ai.openai.embedding-model=text-embedding-3-small",
        "mopl.elasticsearch.reindex-on-startup=false"
})
@EmbeddedKafka(partitions = 1, topics = {
        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
        ContentSearchKafkaTopics.CONTENT_EMBEDDING,
        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
        ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ContentKafkaEmbeddingIntegrationTest {

    private static final String SEARCH_GROUP = "content-search-embedding-it";
    private static final String EMBEDDING_GROUP = "content-embedding-it";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl").withUsername("mopl").withPassword("mopl")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentSearchRepository searchRepository;
    @MockitoSpyBean private ContentSearchIndexer contentSearchIndexer;
    @MockitoSpyBean private ContentEmbeddingIndexer contentEmbeddingIndexer;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private EmbeddedKafkaBroker broker;

    @Autowired
    @Qualifier("kafkaTemplate")
    private KafkaTemplate<String, Object> sourceTemplate;

    @MockitoSpyBean(name = "contentEmbeddingKafkaTemplate")
    private KafkaTemplate<String, Object> embeddingTemplate;

    @MockitoSpyBean(name = "contentDltKafkaTemplate")
    private KafkaTemplate<Object, Object> dltTemplate;

    @Autowired
    @Qualifier("contentSearchSyncDltRecoverer")
    private DeadLetterPublishingRecoverer searchDltRecoverer;

    @MockitoBean private EmbeddingClient embeddingClient;

    private AdminClient admin;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
    }

    @BeforeEach
    void setUp() {
        registry.getListenerContainers().forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
        searchRepository.deleteAll();
        contentRepository.deleteAllInBatch();
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenReturn(vector(0.01D));
    }

    @AfterEach
    void closeAdmin() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void consumesPreexistingRequestsWithNewGroupAndSkipsDuplicateGeneration() throws Exception {
        MessageListenerContainer listener = embeddingListener();
        listener.stop();
        String freshGroup = "embedding-fresh-" + UUID.randomUUID();
        listener.getContainerProperties().setGroupId(freshGroup);

        Content content = saveContent("before consumer start");
        awaitSearchCommit(sendSearch(content, false));
        assertThat(document(content).getEmbedding()).isNull();

        listener.start();
        ContainerTestUtils.waitForAssignment(listener, 1);
        await(() -> hasEmbedding(content));
        assertThat(document(content).getEmbeddingInputHash()).isEqualTo(document(content).getContentInputHash());
        assertThat(document(content).getReviewCount()).isZero();
        assertThat(document(content).getWatcherCount()).isZero();

        // 일반 색인 재처리도 벡터를 보존하며, 이후 같은 Hash 요청은 생성하지 않는다.
        awaitSearchCommit(sendSearch(content, false));
        RecordMetadata duplicate = embeddingTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                content.getId().toString(), new ContentEmbeddingKafkaEvent(content.getId()))
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        await(() -> committedOffset(freshGroup, duplicate.topic()) > duplicate.offset());
        verify(embeddingClient, times(1)).embed(any(EmbeddingRequest.class));
    }

    @Test
    void slowEmbeddingDoesNotBlockFollowingGeneralIndex() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return vector(0.01D);
        });
        try {
            Content first = saveContent("slow embedding");
            sendSearch(first, false);
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            Content second = saveContent("general index continues");
            awaitSearchCommit(sendSearch(second, false));
            assertThat(document(second).getTitle()).isEqualTo(second.getTitle());
            assertThat(document(second).getEmbedding()).isNull();
            release.countDown();
            await(() -> hasEmbedding(second));
        } finally {
            release.countDown();
        }
    }

    @Test
    void retriesEmbeddingFailureAndEventuallyStoresVector() throws Exception {
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenThrow(new AiClientException("temporary failure", 503, null))
                .thenReturn(vector(0.01D));
        Content content = saveContent("retry embedding");

        sendSearch(content, false);

        await(() -> hasEmbedding(content));
        verify(embeddingClient, times(2)).embed(any(EmbeddingRequest.class));
    }

    @Test
    void exhaustedEmbeddingPausesOnlyEmbeddingPartitionAndKeepsOffset() throws Exception {
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenThrow(new AiClientException("unavailable", 503, null));
        Content first = saveContent("failing embedding");
        awaitSearchCommit(sendSearch(first, false));
        TopicPartition partition = new TopicPartition(ContentSearchKafkaTopics.CONTENT_EMBEDDING, 0);

        await(() -> embeddingListener().isPartitionPaused(partition));
        assertThat(committedOffset(EMBEDDING_GROUP, partition.topic())).isLessThanOrEqualTo(0L);
        Content second = saveContent("unaffected general index");
        awaitSearchCommit(sendSearch(second, false));
        assertThat(document(second).getTitle()).isEqualTo(second.getTitle());
        verify(embeddingClient, times(3)).embed(any(EmbeddingRequest.class));
    }

    @Test
    void embeddingDltAcknowledgementAllowsFollowingRecordToBeProcessed() throws Exception {
        searchListener().stop();
        Content first = saveContent("embedding DLT first record");
        Content second = saveContent("embedding DLT following record");
        contentSearchIndexer.index(first);
        contentSearchIndexer.index(second);
        doAnswer(invocation -> {
            Content content = invocation.getArgument(0);
            if (content.getId().equals(first.getId())) {
                throw new IllegalStateException("record-specific embedding failure");
            }
            return invocation.callRealMethod();
        }).when(contentEmbeddingIndexer).indexForKafka(any(Content.class));

        RecordMetadata firstMetadata = embeddingTemplate.send(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                first.getId().toString(),
                new ContentEmbeddingKafkaEvent(first.getId())
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        await(() -> committedOffset(EMBEDDING_GROUP, firstMetadata.topic()) > firstMetadata.offset());
        assertThat(readFirstDltRecord().key()).isEqualTo(first.getId().toString());

        RecordMetadata secondMetadata = embeddingTemplate.send(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                second.getId().toString(),
                new ContentEmbeddingKafkaEvent(second.getId())
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        await(() -> committedOffset(EMBEDDING_GROUP, secondMetadata.topic()) > secondMetadata.offset());
        await(() -> hasEmbedding(second));
    }

    @ParameterizedTest
    @EnumSource(DltPublishFailure.class)
    void failedEmbeddingDltPublishPausesPartitionAndPreservesSourceOffset(DltPublishFailure failure)
            throws Exception {
        MessageListenerContainer search = searchListener();
        search.stop();

        Content content = saveContent("embedding DLT ACK failure");
        contentSearchIndexer.index(content);
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenThrow(new IllegalStateException("unclassified record processing failure"));
        stubDltPublishFailure(failure);

        RecordMetadata sourceRecord = embeddingTemplate.send(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                content.getId().toString(),
                new ContentEmbeddingKafkaEvent(content.getId())
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition sourcePartition = new TopicPartition(sourceRecord.topic(), sourceRecord.partition());

        await(() -> embeddingListener().isPartitionPaused(sourcePartition));
        assertOffsetPreserved(EMBEDDING_GROUP, sourceRecord, sourcePartition, embeddingListener());
        verify(dltTemplate, times(1)).send(any(ProducerRecord.class));
        verify(embeddingClient, times(3)).embed(any(EmbeddingRequest.class));
        org.mockito.Mockito.reset(dltTemplate);
        embeddingListener().resumePartition(sourcePartition);
        await(() -> committedOffset(EMBEDDING_GROUP, sourceRecord.topic()) > sourceRecord.offset());
    }

    @ParameterizedTest
    @EnumSource(DltPublishFailure.class)
    void failedSearchDltPublishPausesOnlySearchPartitionAndPreservesSourceOffset(DltPublishFailure failure)
            throws Exception {
        Content content = saveContent("search DLT ACK failure " + failure);
        doThrow(new IllegalStateException("unclassified search record failure"))
                .when(contentSearchIndexer).index(any(Content.class));
        stubDltPublishFailure(failure);

        RecordMetadata source = sourceTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                content.getId().toString(),
                new com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent(content.getId(), false)
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(source.topic(), source.partition());

        await(() -> searchListener().isPartitionPaused(partition));
        assertOffsetPreserved(SEARCH_GROUP, source, partition, searchListener());
        assertThat(embeddingListener().isPartitionPaused(
                new TopicPartition(ContentSearchKafkaTopics.CONTENT_EMBEDDING, 0))).isFalse();
        verify(dltTemplate, times(1)).send(any(ProducerRecord.class));
        org.mockito.Mockito.reset(dltTemplate);
        searchListener().resumePartition(partition);
        await(() -> committedOffset(SEARCH_GROUP, source.topic()) > source.offset());
    }

    @Test
    void commonElasticsearchFailurePausesSearchAndEmbeddingIndependently() throws Exception {
        Content searchContent = saveContent("common ES search failure");
        Content embeddingContent = saveContent("common ES embedding failure");
        org.springframework.web.client.ResourceAccessException outage =
                new org.springframework.web.client.ResourceAccessException("simulated Elasticsearch outage");
        doThrow(outage).when(contentSearchIndexer).index(any(Content.class));
        doThrow(outage).when(contentEmbeddingIndexer).indexForKafka(any(Content.class));

        RecordMetadata searchSource = sourceTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                searchContent.getId().toString(),
                new com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent(searchContent.getId(), false)
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        RecordMetadata embeddingSource = embeddingTemplate.send(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                embeddingContent.getId().toString(),
                new ContentEmbeddingKafkaEvent(embeddingContent.getId())
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition searchPartition = new TopicPartition(searchSource.topic(), searchSource.partition());
        TopicPartition embeddingPartition = new TopicPartition(embeddingSource.topic(), embeddingSource.partition());

        await(() -> searchListener().isPartitionPaused(searchPartition)
                && embeddingListener().isPartitionPaused(embeddingPartition));
        assertOffsetPreserved(SEARCH_GROUP, searchSource, searchPartition, searchListener());
        assertOffsetPreserved(EMBEDDING_GROUP, embeddingSource, embeddingPartition, embeddingListener());
        assertThat(searchListener().isPartitionPaused(embeddingPartition)).isFalse();
        assertThat(embeddingListener().isPartitionPaused(searchPartition)).isFalse();
    }

    @Test
    void searchDltRecovererPreservesRawKeyBytesAndKeyFailureMetadata() throws Exception {
        byte[] originalKey = new byte[]{0x00, 0x01, (byte) 0xff, 0x41};
        long originalOffset = 1234L;
        UUID contentId = UUID.randomUUID();
        ConsumerRecord<byte[], Object> failedRecord = new ConsumerRecord<>(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                0,
                originalOffset,
                originalKey,
                new com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent(contentId, false)
        );
        failedRecord.headers().add(new RecordHeader(
                KafkaHeaders.DLT_KEY_EXCEPTION_FQCN,
                "example.RawKeyDeserializationFailure".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));

        searchDltRecoverer.accept(
                failedRecord,
                org.mockito.Mockito.mock(Consumer.class),
                new IllegalStateException("simulated key deserialization failure")
        );

        ConsumerRecord<byte[], byte[]> dltRecord = readSearchDltRecordByRawKey(originalKey);
        assertThat(dltRecord.key()).containsExactly(originalKey);
        assertThat(new String(dltRecord.headers().lastHeader(KafkaHeaders.DLT_KEY_EXCEPTION_FQCN).value(),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("example.RawKeyDeserializationFailure");
        assertThat(new String(dltRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC);
        assertThat(java.nio.ByteBuffer.wrap(dltRecord.headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt()).isZero();
        assertThat(java.nio.ByteBuffer.wrap(dltRecord.headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong()).isEqualTo(originalOffset);
    }

    @Test
    void successfulDltAckAdvancesSourceOffsetAndPreservesEventTypeAndKey() throws Exception {
        searchListener().stop();
        Content content = saveContent("embedding DLT ACK success");
        contentSearchIndexer.index(content);
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
                .thenThrow(new IllegalStateException("unclassified record processing failure"));

        RecordMetadata sourceRecord = embeddingTemplate.send(
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                content.getId().toString(),
                new ContentEmbeddingKafkaEvent(content.getId())
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();

        await(() -> committedOffset(EMBEDDING_GROUP, sourceRecord.topic()) > sourceRecord.offset());
        ConsumerRecord<String, byte[]> dltRecord = readFirstDltRecord();
        assertThat(dltRecord.key()).isEqualTo(content.getId().toString());
        assertThat(new String(dltRecord.value(), java.nio.charset.StandardCharsets.UTF_8))
                .contains(content.getId().toString());
        assertThat(new String(
                dltRecord.headers().lastHeader("__TypeId__").value(),
                java.nio.charset.StandardCharsets.UTF_8
        )).isEqualTo("contentEmbedding");
        verify(embeddingClient, times(3)).embed(any(EmbeddingRequest.class));
    }

    @ParameterizedTest
    @EnumSource(PublishFailure.class)
    void publishFailureKeepsOriginalOffset(PublishFailure failure) throws Exception {
        Content content = saveContent("publish failure");
        String key = content.getId().toString();
        if (failure == PublishFailure.SYNCHRONOUS) {
            doThrow(new IllegalStateException(
                    "send failed",
                    new org.apache.kafka.common.errors.TimeoutException("broker timeout")
            )).when(embeddingTemplate)
                    .send(eq(ContentSearchKafkaTopics.CONTENT_EMBEDDING), eq(key), any());
        } else if (failure == PublishFailure.FUTURE) {
            doReturn(CompletableFuture.failedFuture(new IllegalStateException(
                    "broker failed",
                    new org.apache.kafka.common.errors.TimeoutException("broker timeout")
            )))
                    .when(embeddingTemplate).send(eq(ContentSearchKafkaTopics.CONTENT_EMBEDDING), eq(key), any());
        } else {
            CompletableFuture<SendResult<String, Object>> future = mock(CompletableFuture.class);
            when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new TimeoutException("ACK timeout"));
            doReturn(future).when(embeddingTemplate)
                    .send(eq(ContentSearchKafkaTopics.CONTENT_EMBEDDING), eq(key), any());
        }
        RecordMetadata original = sendSearch(content, false);
        TopicPartition partition = new TopicPartition(original.topic(), original.partition());

        await(() -> searchListener().isPartitionPaused(partition));
        assertThat(committedOffset(SEARCH_GROUP, original.topic())).isLessThanOrEqualTo(original.offset());
        assertThat(document(content).getEmbedding()).isNull();
        verify(embeddingTemplate, times(3)).send(eq(ContentSearchKafkaTopics.CONTENT_EMBEDDING), eq(key), any());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void failureAfterPublishAcknowledgementAllowsDuplicateDelivery() throws Exception {
        Content content = saveContent("ACK before original commit");
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            CompletableFuture<SendResult<String, Object>> sent =
                    (CompletableFuture<SendResult<String, Object>>) invocation.callRealMethod();
            return sent.thenApply(result -> {
                if (sends.getAndIncrement() == 0) {
                    // 실제 ACK 후 원본 Listener가 완료되기 전에 실패하는 구간을 재현한다.
                    throw new CompletionException(new IllegalStateException("failure before original commit"));
                }
                return result;
            });
        }).when(embeddingTemplate).send(eq(ContentSearchKafkaTopics.CONTENT_EMBEDDING),
                eq(content.getId().toString()), any());

        awaitSearchCommit(sendSearch(content, false));
        await(() -> committedOffset(EMBEDDING_GROUP, ContentSearchKafkaTopics.CONTENT_EMBEDDING) >= 2);
        assertThat(hasEmbedding(content)).isTrue();
        assertThat(sends.get()).isEqualTo(2);
        verify(embeddingClient, times(1)).embed(any(EmbeddingRequest.class));
    }

    @Test
    void lateOldHashCannotOverwriteNewInput() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
                return vector(0.01D);
            }
            secondEntered.countDown();
            assertThat(releaseSecond.await(30, TimeUnit.SECONDS)).isTrue();
            return vector(0.02D);
        });
        try {
            Content content = saveContent("old input");
            sendSearch(content, false);
            assertThat(firstEntered.await(30, TimeUnit.SECONDS)).isTrue();
            content.update(null, "new input", null, null, null);
            contentRepository.saveAndFlush(content);
            awaitSearchCommit(sendSearch(content, false));

            releaseFirst.countDown();
            assertThat(secondEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(document(content).getEmbedding()).isNull();
            releaseSecond.countDown();
            await(() -> hasEmbedding(content));
            ContentSearchDocument document = document(content);
            assertThat(document.getTitle()).isEqualTo("new input");
            assertThat(document.getEmbedding().getFirst()).isEqualTo(0.02F);
            assertThat(document.getEmbeddingInputHash()).isEqualTo(document.getContentInputHash());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void inFlightEmbeddingCannotRecreateDeletedDocument() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return vector(0.01D);
        });
        try {
            Content content = saveContent("deleted while embedding");
            sendSearch(content, false);
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            content.delete();
            contentRepository.saveAndFlush(content);
            awaitSearchCommit(sendSearch(content, true));
            release.countDown();
            await(() -> committedOffset(EMBEDDING_GROUP, ContentSearchKafkaTopics.CONTENT_EMBEDDING) >= 1);
            assertThat(searchRepository.existsById(content.getId().toString())).isFalse();

            RecordMetadata duplicate = embeddingTemplate.send(ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                    content.getId().toString(), new ContentEmbeddingKafkaEvent(content.getId()))
                    .get(10, TimeUnit.SECONDS).getRecordMetadata();
            await(() -> committedOffset(EMBEDDING_GROUP, duplicate.topic()) > duplicate.offset());
            verify(embeddingClient, times(1)).embed(any(EmbeddingRequest.class));
        } finally {
            release.countDown();
        }
    }

    private Content saveContent(String title) {
        return contentRepository.saveAndFlush(new Content(ContentType.MOVIE, title, "description",
                "https://example.com/image.jpg", ExternalSource.TMDB, UUID.randomUUID().toString(),
                LocalDate.of(2026, 1, 1), null, null, null));
    }

    private RecordMetadata sendSearch(Content content, boolean deleted) throws Exception {
        return sourceTemplate.send(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC, content.getId().toString(),
                new ContentSearchSyncKafkaEvent(content.getId(), deleted))
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private ContentSearchDocument document(Content content) {
        return searchRepository.findById(content.getId().toString()).orElseThrow();
    }

    private boolean hasEmbedding(Content content) {
        return searchRepository.findById(content.getId().toString())
                .map(document -> document.getEmbedding() != null).orElse(false);
    }

    private static EmbeddingResponse vector(double value) {
        return new EmbeddingResponse(Collections.nCopies(1536, value));
    }

    private MessageListenerContainer embeddingListener() {
        return registry.getListenerContainer(ContentEmbeddingKafkaConsumer.LISTENER_ID);
    }

    private MessageListenerContainer searchListener() {
        return registry.getListenerContainers().stream()
                .filter(container -> container.getAssignedPartitions() != null
                        && container.getAssignedPartitions().contains(
                                new TopicPartition(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC, 0)))
                .findFirst().orElseThrow();
    }

    private long committedOffset(String group, String topic) {
        try {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(topic, 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka offset 조회 실패", exception);
        }
    }

    private ConsumerRecord<String, byte[]> readFirstDltRecord() {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                "dlt-test-" + UUID.randomUUID(), "false", broker
        );
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        TopicPartition dltPartition = new TopicPartition(ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT, 0);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                properties, new StringDeserializer(), new ByteArrayDeserializer()
        )) {
            consumer.assign(Collections.singleton(dltPartition));
            consumer.seekToBeginning(Collections.singleton(dltPartition));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }
        throw new AssertionError("Embedding DLT record was not published");
    }

    private ConsumerRecord<byte[], byte[]> readSearchDltRecordByRawKey(byte[] expectedKey) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                "search-dlt-raw-key-" + UUID.randomUUID(), "false", broker
        );
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        TopicPartition partition = new TopicPartition(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(
                properties, new ByteArrayDeserializer(), new ByteArrayDeserializer()
        )) {
            consumer.assign(Collections.singleton(partition));
            consumer.seekToBeginning(Collections.singleton(partition));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    if (java.util.Arrays.equals(expectedKey, record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Search DLT record with raw key was not published");
    }

    private void awaitSearchCommit(RecordMetadata metadata) throws InterruptedException {
        await(() -> committedOffset(SEARCH_GROUP, metadata.topic()) > metadata.offset());
    }

    private void assertOffsetPreserved(
            String group,
            RecordMetadata source,
            TopicPartition partition,
            MessageListenerContainer listener
    ) throws InterruptedException {
        Long lastObserved = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(listener.isPartitionPaused(partition)).isTrue();
            long committed = committedOffset(group, source.topic());
            assertThat(committed).isLessThanOrEqualTo(source.offset());
            if (lastObserved != null) {
                assertThat(committed).isEqualTo(lastObserved);
            }
            lastObserved = committed;
            Thread.sleep(100);
        }
    }

    private void stubDltPublishFailure(DltPublishFailure failure) {
        if (failure == DltPublishFailure.SYNCHRONOUS) {
            doThrow(new IllegalStateException("synchronous DLT send failure"))
                    .when(dltTemplate).send(any(ProducerRecord.class));
        } else if (failure == DltPublishFailure.FUTURE) {
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT ACK failed")))
                    .when(dltTemplate).send(any(ProducerRecord.class));
        } else {
            CompletableFuture<SendResult<Object, Object>> future = mock(CompletableFuture.class);
            try {
                when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                        .thenThrow(new TimeoutException("DLT ACK timeout"));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            doReturn(future).when(dltTemplate).send(any(ProducerRecord.class));
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("비동기 Kafka 처리 완료").isTrue();
    }

    enum PublishFailure {
        SYNCHRONOUS, FUTURE, TIMEOUT
    }

    enum DltPublishFailure {
        SYNCHRONOUS, FUTURE, TIMEOUT
    }
}
