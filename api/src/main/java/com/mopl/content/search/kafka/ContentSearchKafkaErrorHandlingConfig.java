package com.mopl.content.search.kafka;

import com.mopl.infrastructure.kafka.KafkaListenerFailureRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
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
            @Value("${mopl.kafka.content-search-sync.retry-interval-ms:${mopl.kafka.consumer.retry-interval-ms:1000}}") long retryIntervalMs,
            @Value("${mopl.kafka.content-search-sync.retry-max-retries:${mopl.kafka.consumer.retry-max-retries:${mopl.kafka.consumer.retry-max-attempts:3}}}") long maxRetries
    ) {
        return errorHandler(
                dltRecoverer, pauseRecoverer, classifier, "content-search-sync", retryIntervalMs, maxRetries
        );
    }

    @Bean
    public DefaultErrorHandler contentEmbeddingErrorHandler(
            @Qualifier("contentEmbeddingDltRecoverer") DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            @Value("${mopl.kafka.embedding.consumer.retry-interval-ms:${mopl.kafka.consumer.retry-interval-ms:1000}}") long retryIntervalMs,
            @Value("${mopl.kafka.embedding.consumer.retry-max-retries:${mopl.kafka.consumer.retry-max-retries:${mopl.kafka.consumer.retry-max-attempts:3}}}") long maxRetries
    ) {
        return errorHandler(
                dltRecoverer, pauseRecoverer, classifier, "content-embedding", retryIntervalMs, maxRetries
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> contentSearchSyncKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("contentSearchSyncErrorHandler") DefaultErrorHandler errorHandler
    ) {
        return factory(consumerFactory, errorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> contentEmbeddingKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("contentEmbeddingErrorHandler") DefaultErrorHandler errorHandler
    ) {
        return factory(consumerFactory, errorHandler);
    }

    private DefaultErrorHandler errorHandler(
            DeadLetterPublishingRecoverer dltRecoverer,
            KafkaListenerFailureRecoverer pauseRecoverer,
            ContentSearchKafkaFailureClassifier classifier,
            String listenerName,
            long retryIntervalMs,
            long maxRetries
    ) {
        if (retryIntervalMs < 0 || maxRetries < 0) {
            throw new IllegalArgumentException("Kafka 재시도 간격과 횟수는 음수일 수 없습니다.");
        }
        ContentSearchKafkaRecordRecoverer recoverer = new ContentSearchKafkaRecordRecoverer(
                dltRecoverer, pauseRecoverer, classifier, listenerName
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
            DefaultErrorHandler errorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
