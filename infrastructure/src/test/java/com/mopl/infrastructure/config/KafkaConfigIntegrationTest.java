package com.mopl.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
	"spring.kafka.consumer.group-id=kafka-config-integration-test"
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

        @KafkaListener(
                topics = TEST_TOPIC,
                groupId = GROUP_ID,
                containerFactory = "kafkaListenerContainerFactory"
        )
        void consume(ContentSearchSyncKafkaEvent event) {
            events.add(event);
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
        }
    }
}
