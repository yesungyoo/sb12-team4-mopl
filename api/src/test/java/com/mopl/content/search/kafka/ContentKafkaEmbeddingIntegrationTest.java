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
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
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
        "mopl.ai.enabled=true",
        "ai.openai.api-key=test-key",
        "ai.openai.embedding-model=text-embedding-3-small",
        "mopl.elasticsearch.reindex-on-startup=false"
})
@EmbeddedKafka(partitions = 1, topics = {
        ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
        ContentSearchKafkaTopics.CONTENT_EMBEDDING
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
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private EmbeddedKafkaBroker broker;

    @Autowired
    @Qualifier("kafkaTemplate")
    private KafkaTemplate<String, Object> sourceTemplate;

    @MockitoSpyBean(name = "contentEmbeddingKafkaTemplate")
    private KafkaTemplate<String, Object> embeddingTemplate;

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

    @ParameterizedTest
    @EnumSource(PublishFailure.class)
    void publishFailureKeepsOriginalOffset(PublishFailure failure) throws Exception {
        Content content = saveContent("publish failure");
        String key = content.getId().toString();
        if (failure == PublishFailure.SYNCHRONOUS) {
            doThrow(new IllegalStateException("send failed")).when(embeddingTemplate)
                    .send(eq(ContentSearchKafkaTopics.CONTENT_EMBEDDING), eq(key), any());
        } else if (failure == PublishFailure.FUTURE) {
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker failed")))
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

    private void awaitSearchCommit(RecordMetadata metadata) throws InterruptedException {
        await(() -> committedOffset(SEARCH_GROUP, metadata.topic()) > metadata.offset());
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
}
