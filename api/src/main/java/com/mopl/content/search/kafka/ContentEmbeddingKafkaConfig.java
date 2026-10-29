package com.mopl.content.search.kafka;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ContentEmbeddingKafkaConfig {

    @Bean
    public NewTopic contentEmbeddingTopic(
            @Value("${mopl.kafka.embedding.partitions:1}") int partitions,
            @Value("${mopl.kafka.embedding.replicas:1}") int replicas
    ) {
        if (partitions <= 0 || replicas <= 0) {
            throw new IllegalArgumentException("Embedding Topic 파티션과 복제 수는 양수여야 합니다.");
        }
        return TopicBuilder.name(ContentSearchKafkaTopics.CONTENT_EMBEDDING)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> contentEmbeddingProducerFactory(
            @Qualifier("producerFactory") ProducerFactory<String, Object> commonProducerFactory,
            @Value("${mopl.kafka.embedding.producer.max-block-ms:3000}") long maxBlockMs,
            @Value("${mopl.kafka.embedding.producer.request-timeout-ms:5000}") int requestTimeoutMs,
            @Value("${mopl.kafka.embedding.producer.delivery-timeout-ms:10000}") int deliveryTimeoutMs
    ) {
        if (maxBlockMs <= 0 || requestTimeoutMs <= 0 || deliveryTimeoutMs < requestTimeoutMs) {
            throw new IllegalArgumentException(
                    "Embedding Producer timeout은 양수이며 delivery.timeout.ms >= request.timeout.ms여야 합니다."
            );
        }

        Map<String, Object> properties = new HashMap<>(commonProducerFactory.getConfigurationProperties());
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, Object> contentEmbeddingKafkaTemplate(
            @Qualifier("contentEmbeddingProducerFactory") ProducerFactory<String, Object> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }
}
