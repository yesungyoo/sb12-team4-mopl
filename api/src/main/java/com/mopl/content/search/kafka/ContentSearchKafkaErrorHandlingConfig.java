package com.mopl.content.search.kafka;

import com.mopl.infrastructure.kafka.KafkaListenerFailureRecoverer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class ContentSearchKafkaErrorHandlingConfig {

    @Bean
    public ContentSearchKafkaFailureClassifier contentSearchKafkaFailureClassifier() {
        return new ContentSearchKafkaFailureClassifier();
    }

    @Bean
    public DefaultErrorHandler contentSearchSyncErrorHandler(
            @Qualifier("contentSearchSyncDltRecoverer") DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator,
            @Value("${mopl.kafka.content-search-sync.retry-interval-ms:${mopl.kafka.consumer.retry-interval-ms:1000}}") long retryIntervalMs,
            @Value("${mopl.kafka.content-search-sync.retry-max-retries:${mopl.kafka.consumer.retry-max-retries:${mopl.kafka.consumer.retry-max-attempts:3}}}") long maxRetries
    ) {
        return errorHandler(
                dltRecoverer, pauseRecoverer, classifier, autoResumeCoordinator,
                ContentSearchSyncKafkaConsumer.LISTENER_ID, retryIntervalMs, maxRetries
        );
    }

    @Bean
    public DefaultErrorHandler contentEmbeddingErrorHandler(
            @Qualifier("contentEmbeddingDltRecoverer") DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator,
            @Value("${mopl.kafka.embedding.consumer.retry-interval-ms:${mopl.kafka.consumer.retry-interval-ms:1000}}") long retryIntervalMs,
            @Value("${mopl.kafka.embedding.consumer.retry-max-retries:${mopl.kafka.consumer.retry-max-retries:${mopl.kafka.consumer.retry-max-attempts:3}}}") long maxRetries
    ) {
        return errorHandler(
                dltRecoverer, pauseRecoverer, classifier, autoResumeCoordinator,
                ContentEmbeddingKafkaConsumer.LISTENER_ID, retryIntervalMs, maxRetries
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> contentSearchSyncKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("contentSearchSyncErrorHandler") DefaultErrorHandler errorHandler,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator
    ) {
        return factory(
                consumerFactory, errorHandler, ContentSearchSyncKafkaConsumer.LISTENER_ID, autoResumeCoordinator
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> contentEmbeddingKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("contentEmbeddingErrorHandler") DefaultErrorHandler errorHandler,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator
    ) {
        return factory(
                consumerFactory, errorHandler, ContentEmbeddingKafkaConsumer.LISTENER_ID, autoResumeCoordinator
        );
    }

    private DefaultErrorHandler errorHandler(
            DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator,
            String listenerName,
            long retryIntervalMs,
            long maxRetries
    ) {
        if (retryIntervalMs < 0 || maxRetries < 0) {
            throw new IllegalArgumentException("Kafka 재시도 간격과 횟수는 음수일 수 없습니다.");
        }
        ContentSearchKafkaRecordRecoverer recoverer = new ContentSearchKafkaRecordRecoverer(
                dltRecoverer, pauseRecoverer, classifier, autoResumeCoordinator, listenerName
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, maxRetries)
        );
        errorHandler.setBackOffFunction((record, exception) -> classifier.shouldSkipRetry(exception)
                ? new FixedBackOff(0L, 0L)
                : new FixedBackOff(retryIntervalMs, maxRetries));
        errorHandler.setAckAfterHandle(true);
        return errorHandler;
    }

    private ConcurrentKafkaListenerContainerFactory<String, Object> factory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler errorHandler,
            String listenerId,
            ContentSearchKafkaAutoResumeCoordinator autoResumeCoordinator
    ) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(
                    Consumer<?, ?> consumer,
                    java.util.Collection<TopicPartition> partitions
            ) {
                autoResumeCoordinator.onPartitionsRevoked(listenerId, partitions);
            }

            @Override
            public void onPartitionsAssigned(
                    Consumer<?, ?> consumer,
                    java.util.Collection<TopicPartition> partitions
            ) {
                autoResumeCoordinator.onPartitionsAssigned(listenerId, partitions);
            }

            @Override
            public void onPartitionsLost(Consumer<?, ?> consumer, java.util.Collection<TopicPartition> partitions) {
                autoResumeCoordinator.onPartitionsRevoked(listenerId, partitions);
            }
        });
        return factory;
    }
}
