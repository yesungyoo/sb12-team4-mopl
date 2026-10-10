package com.mopl.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaConfigTest {

    private static final String TOPIC = "content-search-sync-test";
    private static final String TYPE_ID_HEADER = "__TypeId__";

    private KafkaConfig kafkaConfig;

    @BeforeEach
    void setUp() {
        kafkaConfig = new KafkaConfig();

        ReflectionTestUtils.setField(
                kafkaConfig,
                "bootstrapServers",
                "localhost:9092"
        );

        ReflectionTestUtils.setField(
                kafkaConfig,
                "groupId",
                "kafka-config-test"
        );
    }

    @Test
    void configuresFactoriesWithErrorHandlingAndTypeMappings() {
        Map<String, Object> producerProperties =
                producerProperties();

        assertThat(
                producerProperties.get(
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG
                )
        ).isEqualTo(JsonSerializer.class);

        assertThat(
                producerProperties.get(
                        JsonSerializer.TYPE_MAPPINGS
                )
        ).isEqualTo(KafkaConfig.PRODUCER_TYPE_MAPPINGS);

        Map<String, Object> consumerProperties =
                consumerProperties();

        assertThat(
                consumerProperties.get(
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG
                )
        ).isEqualTo(ErrorHandlingDeserializer.class);

        assertThat(
                consumerProperties.get(
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG
                )
        ).isEqualTo(ErrorHandlingDeserializer.class);

        assertThat(
                consumerProperties.get(
                        ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS
                )
        ).isEqualTo(
                org.apache.kafka.common.serialization.StringDeserializer.class
        );

        assertThat(
                consumerProperties.get(
                        ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS
                )
        ).isEqualTo(JsonDeserializer.class);

        assertThat(
                consumerProperties.get(
                        JsonDeserializer.TYPE_MAPPINGS
                )
        ).isEqualTo(KafkaConfig.CONSUMER_TYPE_MAPPINGS);
    }

    @Test
    void serializesContentSearchSyncEventWithLogicalTypeId() {
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        UUID.randomUUID(),
                        false
                );

        RecordHeaders headers = new RecordHeaders();

        try (JsonSerializer<Object> serializer = createSerializer()) {
            serializer.serialize(
                    TOPIC,
                    headers,
                    event
            );
        }

        Header typeId = headers.lastHeader(TYPE_ID_HEADER);

        assertThat(typeId).isNotNull();
        assertThat(
                new String(
                        typeId.value(),
                        StandardCharsets.UTF_8
                )
        ).isEqualTo(KafkaConfig.CONTENT_SEARCH_SYNC_TYPE_ID);
    }

    @Test
    void deserializesLogicalContentSearchSyncTypeId() {
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        UUID.randomUUID(),
                        false
                );

        SerializedMessage message = serialize(event);

        Object deserialized = deserialize(
                message.headers(),
                message.value()
        );

        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    void deserializesLegacyContentSearchSyncClassNameTypeId() {
        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        UUID.randomUUID(),
                        true
                );

        SerializedMessage message = serialize(event);

        message.headers().remove(TYPE_ID_HEADER);
        message.headers().add(
                TYPE_ID_HEADER,
                KafkaConfig.LEGACY_CONTENT_SEARCH_SYNC_TYPE_ID
                        .getBytes(StandardCharsets.UTF_8)
        );

        Object deserialized = deserialize(
                message.headers(),
                message.value()
        );

        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    void keepsDeserializationOfOtherKafkaMessageTypes() {
        OtherKafkaEvent event = new OtherKafkaEvent("unchanged");
        SerializedMessage message = serialize(event);

        Header typeId = message.headers().lastHeader(TYPE_ID_HEADER);

        assertThat(
                new String(
                        typeId.value(),
                        StandardCharsets.UTF_8
                )
        ).isEqualTo(OtherKafkaEvent.class.getName());

        Object deserialized = deserialize(
                message.headers(),
                message.value()
        );

        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    void capturesInvalidPayloadWithErrorHandlingDeserializer() {
        RecordHeaders headers = new RecordHeaders();

        headers.add(
                TYPE_ID_HEADER,
                KafkaConfig.CONTENT_SEARCH_SYNC_TYPE_ID
                        .getBytes(StandardCharsets.UTF_8)
        );

        Object deserialized = deserialize(
                headers,
                "not-json".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(deserialized).isNull();
        assertThat(
                headers.lastHeader(
                        SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER
                )
        ).isNotNull();
    }

    private SerializedMessage serialize(Object value) {
        RecordHeaders headers = new RecordHeaders();
        byte[] serialized;

        try (JsonSerializer<Object> serializer = createSerializer()) {
            serialized = serializer.serialize(
                    TOPIC,
                    headers,
                    value
            );
        }

        return new SerializedMessage(
                headers,
                serialized
        );
    }

    private JsonSerializer<Object> createSerializer() {
        JsonSerializer<Object> serializer = new JsonSerializer<>();

        serializer.configure(
                producerProperties(),
                false
        );

        return serializer;
    }

    private Object deserialize(
            RecordHeaders headers,
            byte[] value
    ) {
        try (
                ErrorHandlingDeserializer<Object> deserializer =
                        new ErrorHandlingDeserializer<>()
        ) {
            deserializer.configure(
                    consumerProperties(),
                    false
            );

            return deserializer.deserialize(
                    TOPIC,
                    headers,
                    value
            );
        }
    }

    private Map<String, Object> producerProperties() {
        ProducerFactory<String, Object> producerFactory =
                kafkaConfig.producerFactory();

        return producerFactory.getConfigurationProperties();
    }

    private Map<String, Object> consumerProperties() {
        ConsumerFactory<String, Object> consumerFactory =
                kafkaConfig.consumerFactory();

        return consumerFactory.getConfigurationProperties();
    }

    private record SerializedMessage(
            RecordHeaders headers,
            byte[] value
    ) {
    }

    record OtherKafkaEvent(String value) {
    }
}
