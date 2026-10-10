package com.mopl.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(
	classes = {
		KafkaConfig.class,
		KafkaConfigIntegrationTest.ListenerConfiguration.class
	}
)
@TestPropertySource(properties = {
	"spring.kafka.bootstrap-servers=127.0.0.1:9092",
	"spring.kafka.consumer.group-id=kafka-config-integration-test",
	"mopl.kafka.consumer.retry-interval-ms=100",
	"mopl.kafka.consumer.retry-max-attempts=2"
})
@EmbeddedKafka(
	partitions = 1,
	topics = KafkaConfigIntegrationTest.TEST_TOPIC,
	ports = 9092,
	bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DirtiesContext
class KafkaConfigIntegrationTest {

    static final String TEST_TOPIC = "content-search-sync-contract-test";

    private static final String TEST_LISTENER_ID =
            "content-search-sync-contract-listener";

    private static final String GROUP_ID =
            "kafka-config-integration-test";

    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @Autowired
    private TestListener testListener;

    @BeforeEach
    void setUp() {
        for (
                MessageListenerContainer listenerContainer
                : listenerEndpointRegistry.getListenerContainers()
        ) {
            ContainerTestUtils.waitForAssignment(
                    listenerContainer,
                    1
            );
        }

        testListener.clear();
    }

    @Test
    void consumesLogicalAndLegacyTypeIds() throws Exception {
        UUID logicalContentId = UUID.randomUUID();

        sendRaw(
                KafkaConfig.CONTENT_SEARCH_SYNC_TYPE_ID,
                logicalContentId,
                false
        );

        assertThat(testListener.poll())
                .isEqualTo(
                        new ContentSearchSyncKafkaEvent(
                                logicalContentId,
                                false
                        )
                );

        UUID legacyContentId = UUID.randomUUID();

        sendRaw(
                KafkaConfig.LEGACY_CONTENT_SEARCH_SYNC_TYPE_ID,
                legacyContentId,
                true
        );

        assertThat(testListener.poll())
                .isEqualTo(
                        new ContentSearchSyncKafkaEvent(
                                legacyContentId,
                                true
                        )
                );
    }

    @Test
    void advancesFailedOffsetAndConsumesFollowingValidMessage()
            throws Exception {
        RecordMetadata invalidMetadata = sendRaw(
                KafkaConfig.CONTENT_SEARCH_SYNC_TYPE_ID,
                "not-json".getBytes(StandardCharsets.UTF_8)
        );

        try (
                AdminClient adminClient = AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                embeddedKafkaBroker.getBrokersAsString()
                        )
                )
        ) {
            awaitCommittedOffset(
                    adminClient,
                    invalidMetadata.offset() + 1
            );

            assertThat(
                    testListener.poll(
                            300,
                            TimeUnit.MILLISECONDS
                    )
            ).isNull();

            UUID contentId = UUID.randomUUID();

            RecordMetadata validMetadata = sendRaw(
                    KafkaConfig.CONTENT_SEARCH_SYNC_TYPE_ID,
                    contentId,
                    false
            );

            assertThat(testListener.poll())
                    .isEqualTo(
                            new ContentSearchSyncKafkaEvent(
                                    contentId,
                                    false
                            )
                    );

            awaitCommittedOffset(
                    adminClient,
                    validMetadata.offset() + 1
            );
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void retriesListenerFailureWithIntervalThenPausesWithoutAdvancingOffset()
            throws Exception {
        UUID contentId = UUID.randomUUID();
        int expectedAttempts = 3;
        testListener.failOn(contentId, expectedAttempts);

        RecordMetadata failedMetadata = sendRaw(
                KafkaConfig.CONTENT_SEARCH_SYNC_TYPE_ID,
                contentId,
                false
        );

        assertThat(testListener.awaitFailureAttempts())
                .as("최초 처리 1회와 재시도 2회가 수행되어야 한다.")
                .isTrue();

        List<Long> attemptTimes = testListener.failureAttemptTimes();
        assertThat(attemptTimes).hasSize(expectedAttempts);
        assertThat(
                TimeUnit.NANOSECONDS.toMillis(
                        attemptTimes.get(1) - attemptTimes.get(0)
                )
        ).isGreaterThanOrEqualTo(50L);
        assertThat(
                TimeUnit.NANOSECONDS.toMillis(
                        attemptTimes.get(2) - attemptTimes.get(1)
                )
        ).isGreaterThanOrEqualTo(50L);

        Thread.sleep(400);

        assertThat(testListener.failureAttemptCount())
                .as("재시도 소진 후 무한 반복하지 않아야 한다.")
                .isEqualTo(expectedAttempts);

        TopicPartition topicPartition = new TopicPartition(
                TEST_TOPIC,
                failedMetadata.partition()
        );
        MessageListenerContainer listenerContainer =
                listenerEndpointRegistry.getListenerContainer(
                        TEST_LISTENER_ID
                );
        assertThat(listenerContainer).isNotNull();
        MessageListenerContainer partitionContainer =
                listenerContainer.getContainerFor(
                        topicPartition.topic(),
                        topicPartition.partition()
                );
        awaitPartitionPauseRequested(partitionContainer, topicPartition);

        listenerContainer.enforceRebalance();
        Thread.sleep(400);

        MessageListenerContainer reassignedContainer =
                listenerContainer.getContainerFor(
                        topicPartition.topic(),
                        topicPartition.partition()
                );
        assertThat(reassignedContainer.isPartitionPauseRequested(topicPartition))
                .as("재할당 후에도 실패 파티션 중지 요청을 유지해야 한다.")
                .isTrue();
        assertThat(testListener.failureAttemptCount())
                .as("재할당 후 실패 레코드를 다시 호출하면 안 된다.")
                .isEqualTo(expectedAttempts);

        try (
                AdminClient adminClient = AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                embeddedKafkaBroker.getBrokersAsString()
                        )
                )
        ) {
            OffsetAndMetadata committed = adminClient
                    .listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata()
                    .get()
                    .get(topicPartition);

            assertThat(committed == null
                    || committed.offset() <= failedMetadata.offset())
                    .as("일반 Listener 실패 offset은 진행하면 안 된다.")
                    .isTrue();
        }
    }

    private void awaitPartitionPauseRequested(
            MessageListenerContainer listenerContainer,
            TopicPartition topicPartition
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (listenerContainer.isPartitionPauseRequested(topicPartition)) {
                return;
            }

            Thread.sleep(50);
        }

        assertThat(listenerContainer.isPartitionPauseRequested(topicPartition))
                .as("재시도 소진 후 실패 파티션 중지를 요청해야 한다.")
                .isTrue();
    }

    private RecordMetadata sendRaw(
            String typeId,
            UUID contentId,
            boolean deleted
    ) throws Exception {
        String json =
                "{\"contentId\":\""
                        + contentId
                        + "\",\"deleted\":"
                        + deleted
                        + "}";

        return sendRaw(
                typeId,
                json.getBytes(StandardCharsets.UTF_8)
        );
    }

    private RecordMetadata sendRaw(
            String typeId,
            byte[] value
    ) throws Exception {
        Map<String, Object> properties = new HashMap<>(
                KafkaTestUtils.producerProps(embeddedKafkaBroker)
        );

        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class
        );

        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class
        );

        try (
                KafkaProducer<String, byte[]> producer =
                        new KafkaProducer<>(properties)
        ) {
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(
                            TEST_TOPIC,
                            0,
                            "content-key",
                            value
                    );

            record.headers().add(
                    TYPE_ID_HEADER,
                    typeId.getBytes(StandardCharsets.UTF_8)
            );

            return producer.send(record).get(
                    TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void awaitCommittedOffset(
            AdminClient adminClient,
            long expectedOffset
    ) throws Exception {
        TopicPartition topicPartition =
                new TopicPartition(
                        TEST_TOPIC,
                        0
                );

        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        OffsetAndMetadata committed = null;

        while (System.nanoTime() < deadline) {
            committed = adminClient
                    .listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata()
                    .get()
                    .get(topicPartition);

            if (
                    committed != null
                            && committed.offset() >= expectedOffset
            ) {
                return;
            }

            Thread.sleep(100);
        }

        assertThat(committed)
                .as("복구 처리된 Kafka offset이 commit되어야 한다.")
                .isNotNull();

        assertThat(committed.offset())
                .as("기대 Kafka commit offset")
                .isGreaterThanOrEqualTo(expectedOffset);
    }

	@EnableKafka
	@Configuration
	static class ListenerConfiguration {

		@Bean
		TestListener testListener() {
			return new TestListener();
		}
	}

    static class TestListener {

        private final BlockingQueue<ContentSearchSyncKafkaEvent> events =
                new LinkedBlockingQueue<>();
        private final AtomicInteger failureAttempts = new AtomicInteger();
        private final List<Long> failureAttemptTimes =
                new CopyOnWriteArrayList<>();
        private volatile UUID failedContentId;
        private volatile CountDownLatch failureAttemptLatch =
                new CountDownLatch(0);

        @KafkaListener(
                id = TEST_LISTENER_ID,
                topics = TEST_TOPIC,
                groupId = GROUP_ID,
                containerFactory = "kafkaListenerContainerFactory"
        )
        void consume(ContentSearchSyncKafkaEvent event) {
            if (event.contentId().equals(failedContentId)) {
                failureAttemptTimes.add(System.nanoTime());
                failureAttempts.incrementAndGet();
                failureAttemptLatch.countDown();
                throw new IllegalStateException(
                        "테스트용 Elasticsearch 장애"
                );
            }

            events.add(event);
        }

        void failOn(UUID contentId, int expectedAttempts) {
            failedContentId = contentId;
            failureAttempts.set(0);
            failureAttemptTimes.clear();
            failureAttemptLatch = new CountDownLatch(expectedAttempts);
        }

        boolean awaitFailureAttempts() throws InterruptedException {
            return failureAttemptLatch.await(
                    TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }

        int failureAttemptCount() {
            return failureAttempts.get();
        }

        List<Long> failureAttemptTimes() {
            return List.copyOf(failureAttemptTimes);
        }

        ContentSearchSyncKafkaEvent poll() throws InterruptedException {
            return events.poll(
                    TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }

        ContentSearchSyncKafkaEvent poll(
                long timeout,
                TimeUnit unit
        ) throws InterruptedException {
            return events.poll(
                    timeout,
                    unit
            );
        }

        void clear() {
            events.clear();
            failedContentId = null;
            failureAttempts.set(0);
            failureAttemptTimes.clear();
            failureAttemptLatch = new CountDownLatch(0);
        }
    }
}
