package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
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
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.ToxicList;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
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
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.kafka.consumer.group-id=content-search-embedding-it",
        "spring.datasource.hikari.connection-timeout=2500",
        "spring.datasource.hikari.data-source-properties.connectTimeout=2000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2000",
        "mopl.kafka.embedding.group-id=content-embedding-it",
        "mopl.kafka.consumer.retry-interval-ms=100",
        "mopl.kafka.consumer.retry-max-retries=2",
        "mopl.kafka.dlt.producer.request-timeout-ms=100",
        "mopl.kafka.dlt.producer.delivery-timeout-ms=200",
        "mopl.kafka.dlt.producer.ack-timeout-ms=300",
        "mopl.kafka.auto-resume.probe-interval-ms=100",
        "mopl.kafka.auto-resume.probe-timeout-ms=2000",
        "mopl.kafka.auto-resume.consecutive-successes=3",
        "mopl.kafka.auto-resume.minimum-pause-ms=100",
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

    private static final Logger log = LoggerFactory.getLogger(ContentKafkaEmbeddingIntegrationTest.class);
    private static final String SEARCH_GROUP = "content-search-embedding-it";
    private static final String EMBEDDING_GROUP = "content-embedding-it";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ELASTICSEARCH_READINESS_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEPENDENCY_READINESS_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient ELASTICSEARCH_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static final Network TEST_NETWORK = Network.SHARED;

    private static ToxiproxyContainer.ContainerProxy elasticsearchProxy;
    private static ToxiproxyContainer.ContainerProxy mysqlProxy;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl").withUsername("mopl").withPassword("mopl")
            .withNetwork(TEST_NETWORK)
            .withNetworkAliases("mopl-kafka-it-mysql")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
            .withEnv("xpack.security.enabled", "false")
            .withNetwork(TEST_NETWORK)
            .withNetworkAliases("mopl-kafka-it-elasticsearch")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(TEST_NETWORK);

    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentSearchRepository searchRepository;
    @MockitoSpyBean private ContentSearchIndexer contentSearchIndexer;
    @MockitoSpyBean private ContentEmbeddingIndexer contentEmbeddingIndexer;
    @MockitoSpyBean private ElasticsearchKafkaHealthProbe elasticsearchHealthProbe;
    @MockitoSpyBean private MySqlKafkaHealthProbe mySqlHealthProbe;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean private ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private Environment environment;

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
        properties.add("spring.datasource.url", ContentKafkaEmbeddingIntegrationTest::proxiedMysqlJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.elasticsearch.uris", ContentKafkaEmbeddingIntegrationTest::proxiedElasticsearchUri);
    }

    @BeforeEach
    void setUp() {
        registry.getListenerContainers().forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
        searchRepository.deleteAll();
        contentRepository.deleteAllInBatch();
        when(embeddingClient.embed(any(EmbeddingRequest.class))).thenReturn(vector(0.01D));
        doThrow(new IllegalStateException("ES probe disabled for isolated test"))
                .when(elasticsearchHealthProbe).checkHealthy();
        doThrow(new IllegalStateException("MySQL probe disabled for isolated test"))
                .when(mySqlHealthProbe).checkHealthy();
    }

    @AfterEach
    void closeAdmin() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void searchListenerUsesConfiguredGroupIndependentlyFromListenerId() throws Exception {
        MessageListenerContainer listener = searchListener();

        assertThat(registry.getListenerContainer(ContentSearchSyncKafkaConsumer.LISTENER_ID))
                .isSameAs(listener);
        assertThat(listener.getContainerProperties().getGroupId()).isEqualTo(SEARCH_GROUP);

        var groupDescription = admin.describeConsumerGroups(Collections.singletonList(SEARCH_GROUP))
                .all()
                .get(10, TimeUnit.SECONDS)
                .get(SEARCH_GROUP);
        assertThat(groupDescription.members()).isNotEmpty();
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
        doCallRealMethod().when(elasticsearchHealthProbe).checkHealthy();
        doCallRealMethod().when(mySqlHealthProbe).checkHealthy();
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
        assertRemainsPausedAfterHealthyProbes(embeddingListener(), partition);
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
        doCallRealMethod().when(elasticsearchHealthProbe).checkHealthy();
        doCallRealMethod().when(mySqlHealthProbe).checkHealthy();
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
        assertRemainsPausedAfterHealthyProbes(embeddingListener(), sourcePartition);
        org.mockito.Mockito.reset(dltTemplate);
        embeddingListener().resumePartition(sourcePartition);
        await(() -> committedOffset(EMBEDDING_GROUP, sourceRecord.topic()) > sourceRecord.offset());
    }

    @ParameterizedTest
    @EnumSource(DltPublishFailure.class)
    void failedSearchDltPublishPausesOnlySearchPartitionAndPreservesSourceOffset(DltPublishFailure failure)
            throws Exception {
        doCallRealMethod().when(elasticsearchHealthProbe).checkHealthy();
        doCallRealMethod().when(mySqlHealthProbe).checkHealthy();
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
        assertRemainsPausedAfterHealthyProbes(searchListener(), partition);
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

    @ParameterizedTest
    @EnumSource(SharedDependencyOutage.class)
    void recoversSharedDependencyOutageAndReprocessesPausedRecords(SharedDependencyOutage dependency)
            throws Exception {
        Content content = saveContent("automatic recovery " + dependency);
        String elasticsearchClientEndpoint = environment.getRequiredProperty("spring.elasticsearch.uris");
        String elasticsearchProxyEndpoint = proxiedElasticsearchUri();
        assertThat(elasticsearchClientEndpoint).isEqualTo(elasticsearchProxyEndpoint);
        if (dependency == SharedDependencyOutage.ELASTICSEARCH) {
            assertThat(ELASTICSEARCH.isRunning()).isTrue();
            assertThat(awaitElasticsearchReadiness("before outage", ELASTICSEARCH_READINESS_TIMEOUT)).isTrue();
        }
        if (dependency == SharedDependencyOutage.ELASTICSEARCH) {
            doCallRealMethod().when(elasticsearchHealthProbe).checkHealthy();
        } else {
            doCallRealMethod().when(mySqlHealthProbe).checkHealthy();
        }

        ToxiproxyContainer.ContainerProxy dependencyProxy = dependency == SharedDependencyOutage.ELASTICSEARCH
                ? getElasticsearchProxy()
                : getMysqlProxy();
        List<Toxic> connectionResetToxics = List.of();
        RecordMetadata searchSource = null;
        RecordMetadata embeddingSource = null;
        Map<String, AtomicLong> recovererStartedAt = new ConcurrentHashMap<>();
        Map<String, ContentSearchKafkaFailureClassifier.FailureClassification> recovererClassifications =
                new ConcurrentHashMap<>();
        Throwable testFailure = null;
        doAnswer(invocation -> {
            String listenerId = invocation.getArgument(0);
            ContentSearchKafkaAutoResumeCoordinator.PauseReason pauseReason = invocation.getArgument(3);
            if (pauseReason == ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT) {
                recovererStartedAt.computeIfAbsent(listenerId, ignored -> new AtomicLong())
                        .set(System.nanoTime());
                recovererClassifications.put(listenerId, invocation.getArgument(4));
            }
            return invocation.callRealMethod();
        }).when(autoResumeCoordinator).recordFailure(
                any(String.class), any(TopicPartition.class), anyLong(),
                any(ContentSearchKafkaAutoResumeCoordinator.PauseReason.class),
                any(ContentSearchKafkaFailureClassifier.FailureClassification.class));
        try {
            assertThat(dependency == SharedDependencyOutage.ELASTICSEARCH
                    ? ELASTICSEARCH.isRunning()
                    : MYSQL.isRunning()).isTrue();
            connectionResetToxics = resetExistingConnections(dependencyProxy, "outage-reset");
            if (dependency == SharedDependencyOutage.ELASTICSEARCH) {
                assertThat(elasticsearchHttpResponds(Duration.ofSeconds(3)))
                        .as("Toxiproxy 차단 중 실제 Elasticsearch HTTP 요청이 실패해야 함")
                        .isFalse();
                assertThatThrownBy(() -> elasticsearchHealthProbe.checkHealthy())
                        .as("Toxiproxy 차단 중 Elasticsearch Health Probe가 실패해야 함")
                        .isInstanceOf(RuntimeException.class);
            } else {
                assertThatThrownBy(() -> mySqlHealthProbe.checkHealthy())
                        .as("Toxiproxy 차단 중 MySQL Health Probe가 실패해야 함")
                        .isInstanceOf(RuntimeException.class);
            }

            long outageStartedAt = System.nanoTime();
            searchSource = sendSearch(content, false);
            embeddingSource = embeddingTemplate.send(
                    ContentSearchKafkaTopics.CONTENT_EMBEDDING,
                    content.getId().toString(),
                    new ContentEmbeddingKafkaEvent(content.getId())
            ).get(10, TimeUnit.SECONDS).getRecordMetadata();
            TopicPartition searchPartition = new TopicPartition(searchSource.topic(), searchSource.partition());
            TopicPartition embeddingPartition = new TopicPartition(embeddingSource.topic(), embeddingSource.partition());

            awaitOutagePartitionsPaused(
                    dependency, searchPartition, embeddingPartition, outageStartedAt, recovererStartedAt);
            assertOffsetPreserved(SEARCH_GROUP, searchSource, searchPartition, searchListener());
            assertOffsetPreserved(EMBEDDING_GROUP, embeddingSource, embeddingPartition, embeddingListener());

            if (dependency == SharedDependencyOutage.ELASTICSEARCH) {
                removeToxics(connectionResetToxics);
                connectionResetToxics = List.of();
                assertThat(environment.getRequiredProperty("spring.elasticsearch.uris"))
                        .isEqualTo(elasticsearchProxyEndpoint);
                assertThat(awaitElasticsearchReadiness("after network recovery", ELASTICSEARCH_READINESS_TIMEOUT))
                        .as("연결 복구 후 Elasticsearch HTTP readiness 실패: endpoint=%s", elasticsearchProxyEndpoint)
                        .isTrue();
            } else {
                removeToxics(connectionResetToxics);
                connectionResetToxics = List.of();
                assertThat(awaitMySqlReadiness(DEPENDENCY_READINESS_TIMEOUT))
                        .as("Toxiproxy 연결 복구 후 MySQL Health Probe가 정상화되지 않음")
                        .isTrue();
            }

            RecordMetadata finalSearchSource = searchSource;
            RecordMetadata finalEmbeddingSource = embeddingSource;
            long elasticsearchProbeBaseline = probeInvocationCount(elasticsearchHealthProbe, "checkHealthy");
            long mysqlProbeBaseline = probeInvocationCount(mySqlHealthProbe, "checkHealthy");
            awaitLong(() -> committedOffset(SEARCH_GROUP, finalSearchSource.topic()) > finalSearchSource.offset()
                            && committedOffset(EMBEDDING_GROUP, finalEmbeddingSource.topic())
                            > finalEmbeddingSource.offset()
                            && hasEmbedding(content),
                    () -> recoveryDiagnostics(
                            content,
                            finalSearchSource,
                            finalEmbeddingSource,
                            recovererClassifications,
                            elasticsearchProbeBaseline,
                            mysqlProbeBaseline
                    ));
            assertThat(searchListener().isPartitionPaused(
                    new TopicPartition(searchSource.topic(), searchSource.partition()))).isFalse();
            assertThat(embeddingListener().isPartitionPaused(
                    new TopicPartition(embeddingSource.topic(), embeddingSource.partition()))).isFalse();
            assertThat(document(content).getEmbeddingInputHash()).isEqualTo(document(content).getContentInputHash());
        } catch (Exception | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = restoreDependencyAfterOutage(dependency, connectionResetToxics);
            if (cleanupFailure != null) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    @Test
    void toxicCleanupAttemptsEveryEntryAndRetainsFailuresForRetry() throws Exception {
        Toxic failingToxic = mock(Toxic.class);
        Toxic succeedingToxic = mock(Toxic.class);
        doThrow(new IOException("toxic removal failed")).when(failingToxic).remove();
        List<Toxic> activeToxics = new ArrayList<>(List.of(failingToxic, succeedingToxic));

        assertThatThrownBy(() -> removeToxics(activeToxics))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));

        verify(failingToxic).remove();
        verify(succeedingToxic).remove();
        assertThat(activeToxics).containsExactly(failingToxic);
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
        return registry.getListenerContainer(ContentSearchSyncKafkaConsumer.LISTENER_ID);
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

    private void awaitOutagePartitionsPaused(
            SharedDependencyOutage dependency,
            TopicPartition searchPartition,
            TopicPartition embeddingPartition,
            long outageStartedAt,
            Map<String, AtomicLong> recovererStartedAt
    ) throws InterruptedException {
        long deadline = outageStartedAt + TIMEOUT.toNanos();
        PauseTimeline searchTimeline = new PauseTimeline();
        PauseTimeline embeddingTimeline = new PauseTimeline();
        PauseObservation searchObservation = null;
        PauseObservation embeddingObservation = null;
        while (System.nanoTime() < deadline
                && (!searchTimeline.isPaused() || !embeddingTimeline.isPaused())) {
            long observedAt = System.nanoTime();
            searchObservation = observePartitionPause(
                    ContentSearchSyncKafkaConsumer.LISTENER_ID, searchPartition);
            embeddingObservation = observePartitionPause(
                    ContentEmbeddingKafkaConsumer.LISTENER_ID, embeddingPartition);
            observePauseTimeline("search", searchObservation, searchTimeline, outageStartedAt, observedAt);
            observePauseTimeline("embedding", embeddingObservation, embeddingTimeline, outageStartedAt, observedAt);
            if (!searchTimeline.isPaused() || !embeddingTimeline.isPaused()) {
                Thread.sleep(50);
            }
        }

        // Deadline 경계에서 비동기 Pause가 반영됐을 수 있으므로 마지막 상태를 반드시 다시 읽는다.
        long finalObservedAt = System.nanoTime();
        searchObservation = observePartitionPause(ContentSearchSyncKafkaConsumer.LISTENER_ID, searchPartition);
        embeddingObservation = observePartitionPause(ContentEmbeddingKafkaConsumer.LISTENER_ID, embeddingPartition);
        observePauseTimeline("search", searchObservation, searchTimeline, outageStartedAt, finalObservedAt);
        observePauseTimeline("embedding", embeddingObservation, embeddingTimeline, outageStartedAt, finalObservedAt);

        Long searchRecovererAfterMs = elapsedMillis(outageStartedAt,
                recovererStartedAt.get(ContentSearchSyncKafkaConsumer.LISTENER_ID));
        Long embeddingRecovererAfterMs = elapsedMillis(outageStartedAt,
                recovererStartedAt.get(ContentEmbeddingKafkaConsumer.LISTENER_ID));
        log.info("{} 장애 처리 단계(ms): 일반 색인 recoverer={}, pauseRequested={}, pauseApplied={}; "
                        + "Embedding recoverer={}, pauseRequested={}, pauseApplied={}",
                dependency, searchRecovererAfterMs, searchTimeline.pauseRequestedAfterMs,
                searchTimeline.pauseAppliedAfterMs, embeddingRecovererAfterMs,
                embeddingTimeline.pauseRequestedAfterMs, embeddingTimeline.pauseAppliedAfterMs);

        assertThat(searchObservation.assigned() && searchObservation.paused()
                && embeddingObservation.assigned() && embeddingObservation.paused())
                .as("%s 장애 후 실제 Pause 반영 필요. 일반 색인=%s, Embedding=%s",
                        dependency, searchObservation, embeddingObservation)
                .isTrue();
    }

    private PauseObservation observePartitionPause(String listenerId, TopicPartition partition) {
        MessageListenerContainer listener = registry.getListenerContainer(listenerId);
        if (listener == null) {
            return new PauseObservation(false, false, false, "listener-not-found");
        }
        MessageListenerContainer owner;
        if (listener instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
            owner = concurrent.getContainers().stream()
                    .filter(child -> child.getAssignedPartitions() != null
                            && child.getAssignedPartitions().contains(partition))
                    .findFirst().orElse(null);
        } else {
            owner = listener.getAssignedPartitions() != null
                    && listener.getAssignedPartitions().contains(partition) ? listener : null;
        }
        if (owner == null) {
            return new PauseObservation(false, false, false, listener.getClass().getSimpleName());
        }
        return new PauseObservation(
                true,
                owner.isPartitionPauseRequested(partition),
                owner.isPartitionPaused(partition),
                owner.getClass().getSimpleName());
    }

    private void observePauseTimeline(
            String listenerName,
            PauseObservation observation,
            PauseTimeline timeline,
            long outageStartedAt,
            long observedAt
    ) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(observedAt - outageStartedAt);
        if (observation.assigned() && observation.pauseRequested()
                && timeline.pauseRequestedAfterMs == null) {
            timeline.pauseRequestedAfterMs = elapsedMs;
            log.info("Kafka partition Pause 요청 관측: listener={}, assigned=true, elapsedMs={}, container={}",
                    listenerName, elapsedMs, observation.containerType());
        }
        if (observation.assigned() && observation.paused() && timeline.pauseAppliedAfterMs == null) {
            timeline.pauseAppliedAfterMs = elapsedMs;
            log.info("Kafka partition 실제 Pause 반영 관측: listener={}, assigned=true, elapsedMs={}, container={}",
                    listenerName, elapsedMs, observation.containerType());
        }
    }

    private Long elapsedMillis(long startedAt, AtomicLong eventAt) {
        return eventAt == null || eventAt.get() == 0
                ? null
                : TimeUnit.NANOSECONDS.toMillis(eventAt.get() - startedAt);
    }

    private record PauseObservation(boolean assigned, boolean pauseRequested, boolean paused, String containerType) {
    }

    private static final class PauseTimeline {
        private Long pauseRequestedAfterMs;
        private Long pauseAppliedAfterMs;

        private boolean isPaused() {
            return pauseAppliedAfterMs != null;
        }
    }

    private static synchronized ToxiproxyContainer.ContainerProxy getElasticsearchProxy() {
        if (elasticsearchProxy == null) {
            elasticsearchProxy = TOXIPROXY.getProxy(ELASTICSEARCH, 9200);
        }
        return elasticsearchProxy;
    }

    private static List<Toxic> resetExistingConnections(
            ToxiproxyContainer.ContainerProxy proxy,
            String namePrefix
    ) {
        ToxicList toxics = proxy.toxics();
        List<Toxic> activeToxics = new ArrayList<>(2);
        try {
            activeToxics.add(toxics.resetPeer(namePrefix + "-upstream", ToxicDirection.UPSTREAM, 0));
            activeToxics.add(toxics.resetPeer(namePrefix + "-downstream", ToxicDirection.DOWNSTREAM, 0));
            return activeToxics;
        } catch (IOException exception) {
            IllegalStateException injectionFailure =
                    new IllegalStateException("Toxiproxy reset_peer 장애 주입 실패", exception);
            try {
                removeToxics(activeToxics);
            } catch (RuntimeException cleanupFailure) {
                injectionFailure.addSuppressed(cleanupFailure);
            }
            throw injectionFailure;
        }
    }

    private static void removeToxics(List<Toxic> toxics) {
        RuntimeException removalFailure = null;
        var iterator = toxics.iterator();
        while (iterator.hasNext()) {
            Toxic toxic = iterator.next();
            try {
                toxic.remove();
                iterator.remove();
            } catch (IOException | RuntimeException exception) {
                RuntimeException toxicFailure = new IllegalStateException(
                        "Toxiproxy 장애 toxic 복원 실패: " + toxic.getName(), exception
                );
                if (removalFailure == null) {
                    removalFailure = new IllegalStateException("하나 이상의 Toxiproxy toxic을 제거하지 못했습니다.");
                }
                removalFailure.addSuppressed(toxicFailure);
            }
        }
        if (removalFailure != null) {
            throw removalFailure;
        }
    }

    private RuntimeException restoreDependencyAfterOutage(
            SharedDependencyOutage dependency,
            List<Toxic> activeToxics
    ) {
        RuntimeException cleanupFailure = null;
        try {
            removeToxics(activeToxics);
        } catch (RuntimeException failure) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
        }

        if (dependency == SharedDependencyOutage.ELASTICSEARCH) {
            if (!ELASTICSEARCH.isRunning()) {
                cleanupFailure = appendCleanupFailure(cleanupFailure,
                        new AssertionError("ES Testcontainer가 정리 후 실행 중이 아닙니다."));
            }
            try {
                if (!awaitElasticsearchReadiness("finally network cleanup", DEPENDENCY_READINESS_TIMEOUT)) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure,
                            new AssertionError("후속 테스트 전에 Elasticsearch proxy endpoint 복구 실패: "
                                    + proxiedElasticsearchUri()));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            } catch (RuntimeException | AssertionError failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
        } else {
            if (!MYSQL.isRunning()) {
                cleanupFailure = appendCleanupFailure(cleanupFailure,
                        new AssertionError("MySQL Testcontainer가 정리 후 실행 중이 아닙니다."));
            }
            try {
                if (!awaitMySqlReadiness(DEPENDENCY_READINESS_TIMEOUT)) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure,
                            new AssertionError("후속 테스트 전에 MySQL proxy endpoint 복구 실패: "
                                    + jdbcProxyEndpoint()));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            } catch (RuntimeException | AssertionError failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
        }
        return cleanupFailure;
    }

    private RuntimeException appendCleanupFailure(RuntimeException aggregate, Throwable failure) {
        if (aggregate == null) {
            return new IllegalStateException("Toxiproxy 장애 후 dependency 복구에 실패했습니다.", failure);
        }
        aggregate.addSuppressed(failure);
        return aggregate;
    }

    private static synchronized ToxiproxyContainer.ContainerProxy getMysqlProxy() {
        if (mysqlProxy == null) {
            mysqlProxy = TOXIPROXY.getProxy(MYSQL, 3306);
        }
        return mysqlProxy;
    }

    private static String proxiedElasticsearchUri() {
        ToxiproxyContainer.ContainerProxy proxy = getElasticsearchProxy();
        return "http://" + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort();
    }

    private static String proxiedMysqlJdbcUrl() {
        String originalUrl = MYSQL.getJdbcUrl();
        int authorityStart = originalUrl.indexOf("//") + 2;
        int databasePathStart = originalUrl.indexOf('/', authorityStart);
        ToxiproxyContainer.ContainerProxy proxy = getMysqlProxy();
        String proxyAddress = proxy.getContainerIpAddress() + ":" + proxy.getProxyPort();
        return originalUrl.substring(0, authorityStart) + proxyAddress + originalUrl.substring(databasePathStart);
    }

    private boolean awaitElasticsearchReadiness(String stage, Duration timeout) throws InterruptedException {
        String endpoint = proxiedElasticsearchUri();
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastResult = "request not attempted";
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> response = sendElasticsearchRootRequest(Duration.ofSeconds(2));
                lastResult = "HTTP status=" + response.statusCode();
                if (response.statusCode() == 200) {
                    logElasticsearchState(stage, endpoint, lastResult);
                    return true;
                }
            } catch (IOException | IllegalArgumentException exception) {
                lastResult = "request exception=" + exceptionClassChain(exception);
            }
            Thread.sleep(250);
        }
        logElasticsearchState(stage + " readiness timeout", endpoint, lastResult);
        return false;
    }

    private boolean elasticsearchHttpResponds(Duration timeout) {
        try {
            return sendElasticsearchRootRequest(timeout).statusCode() == 200;
        } catch (IOException | InterruptedException | IllegalArgumentException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.info("Elasticsearch HTTP 장애 주입 확인: endpoint={}, causeTypes={}",
                    proxiedElasticsearchUri(), exceptionClassChain(exception));
            return false;
        }
    }

    private HttpResponse<Void> sendElasticsearchRootRequest(Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxiedElasticsearchUri() + "/"))
                .timeout(timeout)
                .GET()
                .build();
        return ELASTICSEARCH_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private boolean awaitMySqlReadiness(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastFailure = "probe not attempted";
        while (System.nanoTime() < deadline) {
            try {
                mySqlHealthProbe.checkHealthy();
                log.info("MySQL proxy readiness 성공: endpoint={}", jdbcProxyEndpoint());
                return true;
            } catch (RuntimeException exception) {
                lastFailure = exceptionClassChain(exception);
            }
            Thread.sleep(250);
        }
        log.warn("MySQL proxy readiness timeout: endpoint={}, causeTypes={}", jdbcProxyEndpoint(), lastFailure);
        return false;
    }

    private void logElasticsearchState(String stage, String endpoint, String readinessResult) {
        String containerId = ELASTICSEARCH.getContainerId();
        log.info("Elasticsearch 테스트 진단: stage={}, containerId={}, containerRunning={}, "
                        + "containerEndpoint={}, clientAndProbeEndpoint={}, readiness={}",
                stage, containerId, ELASTICSEARCH.isRunning(), ELASTICSEARCH.getHttpHostAddress(), endpoint,
                readinessResult);
    }

    private String jdbcProxyEndpoint() {
        ToxiproxyContainer.ContainerProxy proxy = getMysqlProxy();
        return proxy.getContainerIpAddress() + ":" + proxy.getProxyPort();
    }

    private String exceptionClassChain(Throwable throwable) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (!chain.isEmpty()) {
                chain.append(" -> ");
            }
            chain.append(current.getClass().getName());
        }
        return chain.toString();
    }

    private void awaitLong(BooleanSupplier condition) throws InterruptedException {
        awaitLong(condition, () -> "진단 정보 없음");
    }

    private void awaitLong(BooleanSupplier condition, Supplier<String> diagnostics) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean())
                .as("장애 복구 후 Kafka 자동 재처리 완료. %s", diagnostics.get())
                .isTrue();
    }

    private String recoveryDiagnostics(
            Content content,
            RecordMetadata searchSource,
            RecordMetadata embeddingSource,
            Map<String, ContentSearchKafkaFailureClassifier.FailureClassification> recovererClassifications,
            long elasticsearchProbeBaseline,
            long mysqlProbeBaseline
    ) {
        String embeddingState;
        try {
            embeddingState = Boolean.toString(hasEmbedding(content));
        } catch (RuntimeException exception) {
            embeddingState = "unavailable:" + exception.getClass().getSimpleName();
        }
        return "searchCommitted=" + safeCommittedOffset(SEARCH_GROUP, searchSource.topic())
                + ", embeddingCommitted=" + safeCommittedOffset(EMBEDDING_GROUP, embeddingSource.topic())
                + ", hasEmbedding=" + embeddingState
                + ", searchPause=" + observePartitionPause(
                        ContentSearchSyncKafkaConsumer.LISTENER_ID,
                        new TopicPartition(searchSource.topic(), searchSource.partition()))
                + ", embeddingPause=" + observePartitionPause(
                        ContentEmbeddingKafkaConsumer.LISTENER_ID,
                        new TopicPartition(embeddingSource.topic(), embeddingSource.partition()))
                + ", coordinatorTrackedPauses=" + autoResumeCoordinator.trackedPauseCount()
                + ", recovererClassifications=" + recovererClassifications
                + ", elasticsearchProbeCallsSinceRecovery="
                        + (probeInvocationCount(elasticsearchHealthProbe, "checkHealthy") - elasticsearchProbeBaseline)
                + ", mysqlProbeCallsSinceRecovery="
                        + (probeInvocationCount(mySqlHealthProbe, "checkHealthy") - mysqlProbeBaseline);
    }

    private String safeCommittedOffset(String group, String topic) {
        try {
            return Long.toString(committedOffset(group, topic));
        } catch (RuntimeException exception) {
            return "unavailable:" + exception.getClass().getSimpleName();
        }
    }

    private long probeInvocationCount(Object probeSpy, String methodName) {
        return org.mockito.Mockito.mockingDetails(probeSpy).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(methodName))
                .count();
    }

    private void assertRemainsPausedAfterHealthyProbes(MessageListenerContainer listener, TopicPartition partition)
            throws InterruptedException {
        Thread.sleep(700);
        assertThat(listener.isPartitionPaused(partition)).isTrue();
    }

    enum SharedDependencyOutage {
        ELASTICSEARCH, MYSQL
    }

    enum PublishFailure {
        SYNCHRONOUS, FUTURE, TIMEOUT
    }

    enum DltPublishFailure {
        SYNCHRONOUS, FUTURE, TIMEOUT
    }
}
