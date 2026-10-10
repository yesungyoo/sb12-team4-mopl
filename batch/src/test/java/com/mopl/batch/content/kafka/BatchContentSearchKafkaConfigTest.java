package com.mopl.batch.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

class BatchContentSearchKafkaConfigTest {

    private BatchContentSearchKafkaConfig kafkaConfig;

    @BeforeEach
    void setUp() {
        kafkaConfig = new BatchContentSearchKafkaConfig();
        ReflectionTestUtils.setField(
                kafkaConfig,
                "producerMaxBlockMs",
                2_500L
        );
        ReflectionTestUtils.setField(
                kafkaConfig,
                "producerRequestTimeoutMs",
                4_000
        );
        ReflectionTestUtils.setField(
                kafkaConfig,
                "producerDeliveryTimeoutMs",
                8_000
        );
    }

    @Test
    void appliesProducerTimeoutsOnlyToBatchProducerFactory() {
        Map<String, Object> commonProperties = new HashMap<>();
        commonProperties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "localhost:9092"
        );
        commonProperties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class
        );
        commonProperties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                JsonSerializer.class
        );
        commonProperties.put(
                JsonSerializer.TYPE_MAPPINGS,
                "contentSearchSync:test.Event"
        );
        ProducerFactory<String, Object> commonProducerFactory =
                new DefaultKafkaProducerFactory<>(commonProperties);

        ProducerFactory<String, Object> batchProducerFactory =
                kafkaConfig.batchContentSearchProducerFactory(
                        commonProducerFactory
                );
        Map<String, Object> batchProperties =
                batchProducerFactory.getConfigurationProperties();

        assertThat(batchProperties)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_500L)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 8_000)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0)
                .containsEntry(
                        JsonSerializer.TYPE_MAPPINGS,
                        "contentSearchSync:test.Event"
                );
        assertThat(commonProducerFactory.getConfigurationProperties())
                .doesNotContainKeys(
                        ProducerConfig.MAX_BLOCK_MS_CONFIG,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG
                );
    }

    @Test
    void failsBeanInitializationWhenDeliveryTimeoutIsTooShort() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "mopl.kafka.producer.request-timeout-ms=5000",
                        "mopl.kafka.producer.delivery-timeout-ms=4999"
                )
                .run(context -> {
                    assertThat(context).hasFailed();

                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }

                    assertThat(cause)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("delivery.timeout.ms")
                            .hasMessageContaining("linger.ms")
                            .hasMessageContaining("request.timeout.ms");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BatchContentSearchKafkaConfig.class)
    static class TestConfiguration {

        @Bean("producerFactory")
        ProducerFactory<String, Object> producerFactory() {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    "localhost:9092"
            ));
        }
    }
}
