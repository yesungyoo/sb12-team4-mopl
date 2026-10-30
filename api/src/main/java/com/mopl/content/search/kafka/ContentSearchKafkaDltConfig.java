package com.mopl.content.search.kafka;

import com.mopl.core.common.kafka.ContentEmbeddingKafkaEvent;
import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ContentSearchKafkaDltConfig {

    @Bean
    public NewTopic contentSearchSyncDltTopic(
            @Value("${mopl.kafka.dlt.partitions:1}") int partitions,
            @Value("${mopl.kafka.dlt.replicas:1}") int replicas,
            @Value("${mopl.kafka.dlt.retention-ms:2592000000}") long retentionMs
    ) {
        return dltTopic(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, partitions, replicas, retentionMs);
    }

    @Bean
    public NewTopic contentEmbeddingDltTopic(
            @Value("${mopl.kafka.dlt.partitions:1}") int partitions,
            @Value("${mopl.kafka.dlt.replicas:1}") int replicas,
            @Value("${mopl.kafka.dlt.retention-ms:2592000000}") long retentionMs
    ) {
        return dltTopic(ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT, partitions, replicas, retentionMs);
    }

    @Bean
    public ProducerFactory<Object, Object> contentDltProducerFactory(
            @Qualifier("producerFactory") ProducerFactory<String, Object> commonProducerFactory,
            @Value("${mopl.kafka.dlt.producer.max-block-ms:3000}") long maxBlockMs,
            @Value("${mopl.kafka.dlt.producer.request-timeout-ms:5000}") int requestTimeoutMs,
            @Value("${mopl.kafka.dlt.producer.delivery-timeout-ms:10000}") int deliveryTimeoutMs,
            @Value("${mopl.kafka.dlt.producer.ack-timeout-ms:15000}") long ackTimeoutMs
    ) {
        if (maxBlockMs <= 0 || requestTimeoutMs <= 0 || deliveryTimeoutMs < requestTimeoutMs
                || ackTimeoutMs < deliveryTimeoutMs) {
            throw new IllegalArgumentException(
                    "DLT Producer timeout은 양수이며 delivery.timeout.ms >= request.timeout.ms, "
                            + "ACK 대기 시간 >= delivery.timeout.ms여야 합니다."
            );
        }

        Map<String, Object> properties = new HashMap<>(commonProducerFactory.getConfigurationProperties());
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        DelegatingByTypeSerializer keySerializer = new DelegatingByTypeSerializer(
                Map.of(
                        String.class, new StringSerializer(),
                        byte[].class, new ByteArraySerializer()
                )
        );
        keySerializer.configure(properties, true);
        JsonSerializer<Object> jsonSerializer = new JsonSerializer<>();
        DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(
                Map.of(
                        byte[].class, new ByteArraySerializer(),
                        ContentSearchSyncKafkaEvent.class, new JsonSerializer<>(),
                        ContentEmbeddingKafkaEvent.class, jsonSerializer
                )
        );
        valueSerializer.configure(properties, false);
        return new DefaultKafkaProducerFactory<>(properties, keySerializer, valueSerializer);
    }

    @Bean
    public KafkaTemplate<Object, Object> contentDltKafkaTemplate(
            @Qualifier("contentDltProducerFactory") ProducerFactory<Object, Object> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer contentSearchSyncDltRecoverer(
            @Qualifier("contentDltKafkaTemplate") KafkaTemplate<Object, Object> template,
            @Value("${mopl.kafka.dlt.producer.ack-timeout-ms:15000}") long ackTimeoutMs
    ) {
        return dltRecoverer(template, ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC_DLT, ackTimeoutMs);
    }

    @Bean
    public DeadLetterPublishingRecoverer contentEmbeddingDltRecoverer(
            @Qualifier("contentDltKafkaTemplate") KafkaTemplate<Object, Object> template,
            @Value("${mopl.kafka.dlt.producer.ack-timeout-ms:15000}") long ackTimeoutMs
    ) {
        return dltRecoverer(template, ContentSearchKafkaTopics.CONTENT_EMBEDDING_DLT, ackTimeoutMs);
    }

    private NewTopic dltTopic(String name, int partitions, int replicas, long retentionMs) {
        if (partitions <= 0 || replicas <= 0 || retentionMs <= 0) {
            throw new IllegalArgumentException("DLT 파티션·복제 수·보존 기간은 양수여야 합니다.");
        }
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs)))
                .build();
    }

    private DeadLetterPublishingRecoverer dltRecoverer(
            KafkaTemplate<Object, Object> template,
            String dltTopic,
            long ackTimeoutMs
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(dltTopic, record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofMillis(ackTimeoutMs));
        recoverer.setTimeoutBuffer(0);
        recoverer.setRetainExceptionHeader(false);
        recoverer.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        return recoverer;
    }
}
