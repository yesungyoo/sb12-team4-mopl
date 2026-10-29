package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.config.AiConfig;
import com.mopl.infrastructure.config.KafkaConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

class ContentEmbeddingKafkaConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaConfig.class, ContentEmbeddingKafkaConfig.class,
                    ContentEmbeddingKafkaConsumer.class, ContentEmbeddingKafkaProducer.class,
                    ContentSearchSyncKafkaProducer.class, AiConfig.class, AiAvailability.class)
            .withBean(ContentRepository.class, () -> mock(ContentRepository.class))
            .withBean(ContentEmbeddingIndexer.class, () -> mock(ContentEmbeddingIndexer.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");

    @ParameterizedTest
    @CsvSource({"false,test-key", "true,''"})
    void unavailableAiDoesNotStartListenerAndKeepsOverridesLocal(boolean enabled, String apiKey) {
        contextRunner.withPropertyValues("mopl.ai.enabled=" + enabled, "ai.openai.api-key=" + apiKey)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MessageListenerContainer listener = context.getBean(KafkaListenerEndpointRegistry.class)
                            .getListenerContainer(ContentEmbeddingKafkaConsumer.LISTENER_ID);
                    assertThat(listener).isNotNull();
                    assertThat(listener.isAutoStartup()).isFalse();
                    assertThat(listener.isRunning()).isFalse();
                    assertThat(listener.getContainerProperties().getGroupId())
                            .isEqualTo("mopl-content-embedding-group");
                    assertThat(listener.getContainerProperties().getKafkaConsumerProperties())
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
                    NewTopic topic = context.getBean("contentEmbeddingTopic", NewTopic.class);
                    assertThat(topic.numPartitions()).isEqualTo(1);
                    assertThat(topic.replicationFactor()).isEqualTo((short) 1);

                    ConsumerFactory<?, ?> commonConsumer = context.getBean(ConsumerFactory.class);
                    assertThat(commonConsumer.getConfigurationProperties())
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                            .doesNotContainKey(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);

                    ProducerFactory<?, ?> embeddingProducer = context.getBean(
                            "contentEmbeddingProducerFactory", ProducerFactory.class);
                    assertThat(embeddingProducer.getConfigurationProperties())
                            .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000L)
                            .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000)
                            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
                    assertThat(context.getBean("producerFactory", ProducerFactory.class)
                            .getConfigurationProperties()).doesNotContainKey(ProducerConfig.MAX_BLOCK_MS_CONFIG);
                    assertThat(ReflectionTestUtils.getField(context.getBean(ContentSearchSyncKafkaProducer.class),
                            "kafkaTemplate")).isSameAs(context.getBean("kafkaTemplate"));
                    assertThat(ReflectionTestUtils.getField(context.getBean(ContentEmbeddingKafkaProducer.class),
                            "kafkaTemplate")).isSameAs(context.getBean("contentEmbeddingKafkaTemplate"));
                });
    }

    @Test
    void rejectsInvalidProducerTimeoutConfiguration() {
        contextRunner.withPropertyValues("mopl.ai.enabled=false",
                        "mopl.kafka.embedding.producer.delivery-timeout-ms=1000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotRegisterKafkaComponentsWithoutBootstrapServers() {
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaConfig.class, ContentEmbeddingKafkaConfig.class,
                        ContentEmbeddingKafkaConsumer.class, ContentEmbeddingKafkaProducer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ContentEmbeddingKafkaConsumer.class)
                            .doesNotHaveBean(ContentEmbeddingKafkaProducer.class)
                            .doesNotHaveBean("contentEmbeddingKafkaTemplate");
                });
    }
}
