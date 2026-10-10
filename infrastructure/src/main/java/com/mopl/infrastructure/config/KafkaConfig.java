package com.mopl.infrastructure.config;

import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import com.mopl.infrastructure.kafka.KafkaDeserializationFailureRecoverer;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@EnableKafka
@Configuration
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class KafkaConfig {

  static final String CONTENT_SEARCH_SYNC_TYPE_ID = "contentSearchSync";

  static final String LEGACY_CONTENT_SEARCH_SYNC_TYPE_ID =
      "com.mopl.content.search.kafka.event.ContentSearchSyncKafkaEvent";

  static final String PRODUCER_TYPE_MAPPINGS =
      CONTENT_SEARCH_SYNC_TYPE_ID
          + ":"
          + ContentSearchSyncKafkaEvent.class.getName();

  static final String CONSUMER_TYPE_MAPPINGS =
      PRODUCER_TYPE_MAPPINGS
          + ","
          + LEGACY_CONTENT_SEARCH_SYNC_TYPE_ID
          + ":"
          + ContentSearchSyncKafkaEvent.class.getName();

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${spring.kafka.consumer.group-id:mopl-group}")
  private String groupId;

  // ===== Producer (batch 등에서 KafkaTemplate으로 발행) =====
  @Bean
  public ProducerFactory<String, Object> producerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    props.put(JsonSerializer.TYPE_MAPPINGS, PRODUCER_TYPE_MAPPINGS);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate() {
    return new KafkaTemplate<>(producerFactory());
  }

  // ===== Consumer (api의 @KafkaListener가 사용) =====
  @Bean
  public ConsumerFactory<String, Object> consumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        ErrorHandlingDeserializer.class
    );
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        ErrorHandlingDeserializer.class
    );
    props.put(
        ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
        StringDeserializer.class
    );
    props.put(
        ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
        JsonDeserializer.class
    );
    props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.mopl.*");
    props.put(JsonDeserializer.TYPE_MAPPINGS, CONSUMER_TYPE_MAPPINGS);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  public KafkaDeserializationFailureRecoverer kafkaDeserializationFailureRecoverer() {
    return new KafkaDeserializationFailureRecoverer();
  }

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaDeserializationFailureRecoverer recoverer
  ) {
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer);

    // DeserializationException은 기본 fatal 분류로 즉시 recoverer에 전달된다.
    // recoverer가 성공한 레코드는 컨테이너의 기존 ack 전략으로 offset을 진행한다.
    errorHandler.setAckAfterHandle(true);

    return errorHandler;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
      ConsumerFactory<String, Object> consumerFactory,
      DefaultErrorHandler kafkaErrorHandler
  ) {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    return factory;
  }
}
