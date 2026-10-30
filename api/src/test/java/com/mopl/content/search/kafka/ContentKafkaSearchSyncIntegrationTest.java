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

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.content.service.ContentService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.kafka.consumer.group-id=content-search-dlt-it",
        "mopl.kafka.embedding.group-id=content-embedding-disabled-it",
        "mopl.kafka.consumer.retry-interval-ms=100",
        "mopl.kafka.consumer.retry-max-retries=2",
        "mopl.kafka.dlt.producer.request-timeout-ms=100",
        "mopl.kafka.dlt.producer.delivery-timeout-ms=200",
        "mopl.kafka.dlt.producer.ack-timeout-ms=6000",
        "mopl.ai.enabled=false",
        "mopl.elasticsearch.reindex-on-startup=false"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT,
                ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
class ContentKafkaSearchSyncIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final int KAFKA_PARTITIONS = 1;
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(10);

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("mopl")
                    .withUsername("mopl")
                    .withPassword("mopl")
                    .withStartupTimeout(Duration.ofMinutes(5));

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
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private ContentService contentService;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentSearchRepository contentSearchRepository;

    @MockitoSpyBean
    private ContentSearchIndexer contentSearchIndexer;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoSpyBean(name = "contentDltKafkaTemplate")
    private KafkaTemplate<Object, Object> dltTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private AdminClient adminClient;

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
        MessageListenerContainer embedding = kafkaListenerEndpointRegistry.getListenerContainer(
                ContentEmbeddingKafkaConsumer.LISTENER_ID
        );
        kafkaListenerEndpointRegistry.getListenerContainers().stream()
                .filter(container -> container != embedding)
                .filter(container -> !container.isRunning())
                .forEach(MessageListenerContainer::start);
        waitForKafkaConsumerAssignment();
        adminClient = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString()
        ));

        contentSearchRepository.deleteAll();
        contentRepository.deleteAllInBatch();

        when(
                embeddingClient.embed(
                        any(EmbeddingRequest.class)
                )
        ).thenReturn(
                new EmbeddingResponse(
                        createEmbedding()
                )
        );
    }

    @org.junit.jupiter.api.AfterEach
    void closeAdminClient() {
        if (adminClient != null) {
            adminClient.close(java.time.Duration.ofSeconds(5));
        }
    }

    @Test
    void synchronizesContentCrudToElasticsearchThroughKafka()
            throws InterruptedException {
        ContentCreateRequest createRequest =
                new ContentCreateRequest(
                        ContentType.MOVIE,
                        "Kafka 생성 영화",
                        "Kafka를 통해 Elasticsearch에 동기화되는 콘텐츠",
                        "https://example.com/kafka-created.jpg",
                        LocalDate.of(
                                2026,
                                10,
                                3
                        )
                );

        ContentResponse created =
                contentService.createContent(
                        createRequest
                );

        awaitUntil(
                () -> contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .map(ContentSearchDocument::getTitle)
                        .filter("Kafka 생성 영화"::equals)
                        .isPresent()
        );

        ContentSearchDocument createdDocument =
                contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .orElseThrow();

        assertThat(createdDocument.getTitle())
                .isEqualTo("Kafka 생성 영화");

        assertThat(createdDocument.getDescription())
                .isEqualTo(
                        "Kafka를 통해 Elasticsearch에 동기화되는 콘텐츠"
                );

        ContentUpdateRequest updateRequest =
                new ContentUpdateRequest(
                        ContentType.MOVIE,
                        "Kafka 수정 영화",
                        "수정 이벤트도 Kafka를 통해 동기화",
                        "https://example.com/kafka-updated.jpg",
                        LocalDate.of(
                                2026,
                                10,
                                4
                        )
                );

        contentService.updateContent(
                created.id(),
                updateRequest
        );

        awaitUntil(
                () -> contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .map(ContentSearchDocument::getTitle)
                        .filter("Kafka 수정 영화"::equals)
                        .isPresent()
        );

        ContentSearchDocument updatedDocument =
                contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .orElseThrow();

        assertThat(updatedDocument.getId())
                .isEqualTo(
                        created.id().toString()
                );

        assertThat(updatedDocument.getTitle())
                .isEqualTo("Kafka 수정 영화");

        assertThat(updatedDocument.getDescription())
                .isEqualTo(
                        "수정 이벤트도 Kafka를 통해 동기화"
                );

        contentService.deleteContent(
                created.id()
        );

        awaitUntil(
                () -> !contentSearchRepository.existsById(
                        created.id().toString()
                )
        );

        assertThat(
                contentSearchRepository.existsById(
                        created.id().toString()
                )
        ).isFalse();

        assertThat(
                contentRepository.findByIdAndDeletedAtIsNull(
                        created.id()
                )
        ).isEmpty();

        verifyNoInteractions(embeddingClient);
    }

    @Test
    void exhaustedRecordFailureIsPublishedToSearchDltBeforeSourceOffsetAdvances() throws Exception {
        Content content = contentRepository.saveAndFlush(new Content(
                ContentType.MOVIE,
                "DLT test content",
                "content-specific failure",
                "https://example.com/dlt.jpg",
                ExternalSource.TMDB,
                UUID.randomUUID().toString(),
                LocalDate.of(2026, 1, 1),
                null,
                null,
                null
        ));
        doThrow(new IllegalStateException("unclassified record processing failure"))
                .when(contentSearchIndexer).index(any(Content.class));

        var sourceMetadata = kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                content.getId().toString(),
                new ContentSearchSyncKafkaEvent(content.getId(), false)
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();

        awaitUntil(() -> committedOffset(sourceMetadata.topic()) > sourceMetadata.offset());
        ConsumerRecord<String, byte[]> dltRecord = readSearchDltRecord(content.getId().toString());

        assertThat(dltRecord.key()).isEqualTo(content.getId().toString());
        assertThat(new String(dltRecord.value(), java.nio.charset.StandardCharsets.UTF_8))
                .contains(content.getId().toString());
        assertThat(new String(dltRecord.headers().lastHeader("__TypeId__").value(),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("contentSearchSync");
        assertThat(new String(dltRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC);
        verify(contentSearchIndexer, org.mockito.Mockito.times(3)).index(any(Content.class));
    }

    @Test
    void searchDltAcknowledgementAllowsFollowingRecordToBeProcessed() throws Exception {
        Content first = contentRepository.saveAndFlush(new Content(
                ContentType.MOVIE, "first failed", "record-specific failure", "https://example.com/first.jpg",
                ExternalSource.TMDB, UUID.randomUUID().toString(), LocalDate.of(2026, 1, 1), null, null, null
        ));
        Content second = contentRepository.saveAndFlush(new Content(
                ContentType.MOVIE, "following succeeds", "normal record", "https://example.com/second.jpg",
                ExternalSource.TMDB, UUID.randomUUID().toString(), LocalDate.of(2026, 1, 1), null, null, null
        ));
        doAnswer(invocation -> {
            Content content = invocation.getArgument(0);
            if (content.getId().equals(first.getId())) {
                throw new IllegalStateException("record-specific failure");
            }
            return invocation.callRealMethod();
        }).when(contentSearchIndexer).index(any(Content.class));

        RecordMetadata firstMetadata = kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                first.getId().toString(),
                new ContentSearchSyncKafkaEvent(first.getId(), false)
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitUntil(() -> committedOffset(firstMetadata.topic()) > firstMetadata.offset());
        assertThat(readSearchDltRecord(first.getId().toString()).key()).isEqualTo(first.getId().toString());

        RecordMetadata secondMetadata = kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                second.getId().toString(),
                new ContentSearchSyncKafkaEvent(second.getId(), false)
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitUntil(() -> committedOffset(secondMetadata.topic()) > secondMetadata.offset());
        assertThat(contentSearchRepository.findById(second.getId().toString())).isPresent();
    }

    @ParameterizedTest
    @EnumSource(DltPublishFailure.class)
    void failedSearchDltPublishPausesPartitionAndPreservesSourceOffset(DltPublishFailure failure)
            throws Exception {
        Content content = contentRepository.saveAndFlush(new Content(
                ContentType.MOVIE, "DLT send failure", "record-specific failure", "https://example.com/failure.jpg",
                ExternalSource.TMDB, UUID.randomUUID().toString(), LocalDate.of(2026, 1, 1), null, null, null
        ));
        doThrow(new IllegalStateException("record-specific failure"))
                .when(contentSearchIndexer).index(any(Content.class));
        stubDltPublishFailure(failure);

        RecordMetadata source = kafkaTemplate.send(
                ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
                content.getId().toString(),
                new ContentSearchSyncKafkaEvent(content.getId(), false)
        ).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(source.topic(), source.partition());

        awaitUntil(() -> searchListener().isPartitionPaused(partition));
        assertSearchOffsetPreserved(source, partition);
        verify(dltTemplate, times(1)).send(any(ProducerRecord.class));
        org.mockito.Mockito.reset(dltTemplate);
        searchListener().resumePartition(partition);
        awaitUntil(() -> committedOffset(source.topic()) > source.offset());
    }

    @Test
    void deserializationFailureDltPreservesOriginalRawBytes() throws Exception {
        String key = "raw-deserialization-" + UUID.randomUUID();
        byte[] originalBytes = "{\"contentId\":}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        CountDownLatch dltAckObserved = new CountDownLatch(1);
        CountDownLatch releaseDltAck = new CountDownLatch(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            CompletableFuture<SendResult<Object, Object>> brokerAck =
                    (CompletableFuture<SendResult<Object, Object>>) invocation.callRealMethod();
            CompletableFuture<SendResult<Object, Object>> gatedAck = new CompletableFuture<>();
            brokerAck.whenComplete((result, failure) -> {
                dltAckObserved.countDown();
                try {
                    if (!releaseDltAck.await(5, TimeUnit.SECONDS)) {
                        gatedAck.completeExceptionally(new TimeoutException("test DLT ACK gate timed out"));
                    } else if (failure != null) {
                        gatedAck.completeExceptionally(failure);
                    } else {
                        gatedAck.complete(result);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    gatedAck.completeExceptionally(exception);
                }
            });
            return gatedAck;
        }).when(dltTemplate).send(any(ProducerRecord.class));

        try {
            var sourceMetadata = sendRawRecord(producerProperties, key, originalBytes, "contentSearchSync");
            assertThat(dltAckObserved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(committedOffset(sourceMetadata.topic())).isLessThanOrEqualTo(sourceMetadata.offset());

            releaseDltAck.countDown();
            awaitUntil(() -> committedOffset(sourceMetadata.topic()) > sourceMetadata.offset());
            ConsumerRecord<String, byte[]> dltRecord = readSearchDltRecord(key);

            assertThat(Arrays.equals(dltRecord.value(), originalBytes)).isTrue();
            assertThat(dltRecord.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
            assertThat(new String(dltRecord.headers().lastHeader("__TypeId__").value(),
                    java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("contentSearchSync");
            assertThat(new String(dltRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                    java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(sourceMetadata.topic());
            assertThat(java.nio.ByteBuffer.wrap(dltRecord.headers()
                    .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt())
                    .isEqualTo(sourceMetadata.partition());
            assertThat(java.nio.ByteBuffer.wrap(dltRecord.headers()
                    .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong())
                    .isEqualTo(sourceMetadata.offset());
        } finally {
            releaseDltAck.countDown();
            org.mockito.Mockito.reset(dltTemplate);
        }
    }

    @Test
    void failedDeserializationDltPublishWithDeserializationCausePausesAndKeepsSourceOffset() throws Exception {
        String key = "raw-deserialization-dlt-failure-" + UUID.randomUUID();
        byte[] originalBytes = "{\"contentId\":}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.springframework.kafka.support.serializer.DeserializationException originalFailure =
                new org.springframework.kafka.support.serializer.DeserializationException(
                        "invalid JSON", originalBytes, false, new IllegalArgumentException("parse error")
                );
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT send failed", originalFailure)))
                .when(dltTemplate).send(any(ProducerRecord.class));
        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());

        var source = sendRawRecord(producerProperties, key, originalBytes, "contentSearchSync");
        TopicPartition partition = new TopicPartition(source.topic(), source.partition());
        awaitUntil(() -> searchListener().isPartitionPaused(partition));
        assertSearchOffsetPreserved(source, partition);

        org.mockito.Mockito.reset(dltTemplate);
        searchListener().resumePartition(partition);
        awaitUntil(() -> committedOffset(source.topic()) > source.offset());
    }

    @Test
    void unavailableAiKeepsEmbeddingBacklogAndManualStartPausesWithoutDlt() throws Exception {
        MessageListenerContainer listener = kafkaListenerEndpointRegistry.getListenerContainer(
                ContentEmbeddingKafkaConsumer.LISTENER_ID
        );
        boolean wasRunning = listener.isRunning();
        String previousGroupId = listener.getContainerProperties().getGroupId();
        String testGroupId = "content-embedding-disabled-" + UUID.randomUUID();
        try {
            listener.stop();
            listener.getContainerProperties().setGroupId(testGroupId);
            assertThat(listener.isAutoStartup()).isFalse();
            RecordMetadata source = kafkaTemplate.send(
                    ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                    "disabled-ai-" + UUID.randomUUID(),
                    new ContentEmbeddingKafkaEvent(UUID.randomUUID())
            ).get(10, TimeUnit.SECONDS).getRecordMetadata();
            assertThat(listener.isRunning()).isFalse();
            assertThat(committedOffset(source.topic(), testGroupId)).isEqualTo(-1L);

            listener.start();
            ContainerTestUtils.waitForAssignment(listener, KAFKA_PARTITIONS);
            TopicPartition partition = new TopicPartition(source.topic(), source.partition());
            awaitUntil(() -> listener.isPartitionPaused(partition));
            assertThat(committedOffset(source.topic(), testGroupId)).isLessThanOrEqualTo(source.offset());
            verify(dltTemplate, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
        } finally {
            listener.stop();
            listener.getContainerProperties().setGroupId(previousGroupId);
            if (wasRunning) {
                listener.start();
            }
        }
    }

    private void assertSearchOffsetPreserved(RecordMetadata source, TopicPartition partition)
            throws InterruptedException {
        Long lastObserved = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(searchListener().isPartitionPaused(partition)).isTrue();
            long committed = committedOffset(source.topic());
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
                org.mockito.Mockito.when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                        .thenThrow(new TimeoutException("DLT ACK timeout"));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            doReturn(future).when(dltTemplate).send(any(ProducerRecord.class));
        }
    }

    private MessageListenerContainer searchListener() {
        return kafkaListenerEndpointRegistry.getListenerContainers().stream()
                .filter(container -> container.getAssignedPartitions() != null
                        && container.getAssignedPartitions().contains(
                                new TopicPartition(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC, 0)))
                .findFirst().orElseThrow();
    }

    enum DltPublishFailure {
        SYNCHRONOUS, FUTURE, TIMEOUT
    }

    private void waitForKafkaConsumerAssignment() {
        for (
                MessageListenerContainer listenerContainer
                : kafkaListenerEndpointRegistry.getListenerContainers()
        ) {
            if (listenerContainer.isRunning()) {
                ContainerTestUtils.waitForAssignment(
                        listenerContainer,
                        KAFKA_PARTITIONS
                );
            }
        }
    }

    private long committedOffset(String topic) {
        return committedOffset(topic, "content-search-dlt-it");
    }

    private long committedOffset(String topic, String groupId) {
        try {
            OffsetAndMetadata offset = adminClient.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                    .get(new TopicPartition(topic, 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka offset 조회 실패", exception);
        }
    }

    private org.apache.kafka.clients.producer.RecordMetadata sendRawRecord(
            Properties properties,
            String key,
            byte[] value,
            String typeId
    ) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
                properties, new StringSerializer(), new ByteArraySerializer()
        )) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                    ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC, 0, key, value
            );
            record.headers().add(new RecordHeader(
                    "__TypeId__", typeId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
            return producer.send(record).get(10, TimeUnit.SECONDS);
        }
    }

    private ConsumerRecord<String, byte[]> readSearchDltRecord(String expectedKey) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                "search-dlt-check-" + UUID.randomUUID(), "false", broker
        );
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        TopicPartition partition = new TopicPartition(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, 0);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                properties, new StringDeserializer(), new ByteArrayDeserializer()
        )) {
            consumer.assign(java.util.Set.of(partition));
            consumer.seekToBeginning(java.util.Set.of(partition));
            long deadline = System.nanoTime() + SYNC_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(java.time.Duration.ofMillis(250));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Search DLT record was not published");
    }

    private List<Double> createEmbedding() {
        return IntStream
                .range(
                        0,
                        EMBEDDING_DIMENSIONS
                )
                .mapToObj(index ->
                        index == 0
                                ? 1.0
                                : 0.0
                )
                .toList();
    }

    private void awaitUntil(
            BooleanSupplier condition
    ) throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + SYNC_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            Thread.sleep(100);
        }

        assertThat(condition.getAsBoolean())
                .as(
                        "Kafka 이벤트가 제한 시간 내 Elasticsearch에 반영되어야 한다."
                )
                .isTrue();
    }
}
