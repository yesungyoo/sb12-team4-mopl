package com.mopl.batch.content.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class BatchContentSearchKafkaConfig {

    private static final int PRODUCER_LINGER_MS = 0;

    @Value("${mopl.kafka.producer.max-block-ms:3000}")
    private long producerMaxBlockMs;

    @Value("${mopl.kafka.producer.request-timeout-ms:5000}")
    private int producerRequestTimeoutMs;

    @Value("${mopl.kafka.producer.delivery-timeout-ms:10000}")
    private int producerDeliveryTimeoutMs;

    @Bean("batchContentSearchProducerFactory")
    public ProducerFactory<String, Object> batchContentSearchProducerFactory(
            @Qualifier("producerFactory")
            ProducerFactory<String, Object> commonProducerFactory
    ) {
        long minimumDeliveryTimeoutMs = (long) PRODUCER_LINGER_MS
                + producerRequestTimeoutMs;
        if (producerDeliveryTimeoutMs < minimumDeliveryTimeoutMs) {
            throw new IllegalArgumentException(
                    "Kafka delivery.timeout.ms는 linger.ms와 "
                            + "request.timeout.ms의 합 이상이어야 합니다. "
                            + "deliveryTimeoutMs="
                            + producerDeliveryTimeoutMs
                            + ", lingerMs=" + PRODUCER_LINGER_MS
                            + ", requestTimeoutMs="
                            + producerRequestTimeoutMs
            );
        }

        Map<String, Object> properties = new HashMap<>(
                commonProducerFactory.getConfigurationProperties()
        );
        properties.put(
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                producerMaxBlockMs
        );
        properties.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                producerRequestTimeoutMs
        );
        properties.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                producerDeliveryTimeoutMs
        );
        properties.put(
                ProducerConfig.LINGER_MS_CONFIG,
                PRODUCER_LINGER_MS
        );

        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean("batchContentSearchKafkaTemplate")
    public KafkaTemplate<String, Object> batchContentSearchKafkaTemplate(
            @Qualifier("batchContentSearchProducerFactory")
            ProducerFactory<String, Object> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }
}
