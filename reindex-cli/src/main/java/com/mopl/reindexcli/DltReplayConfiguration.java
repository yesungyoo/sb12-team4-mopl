package com.mopl.reindexcli;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

@Configuration
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class DltReplayConfiguration {

    @Bean
    @Qualifier("dltReplayConsumerFactory")
    public ConsumerFactory<byte[], byte[]> dltReplayConsumerFactory(
            ConsumerFactory<String, Object> commonConsumerFactory
    ) {
        Map<String, Object> properties = new HashMap<>(commonConsumerFactory.getConfigurationProperties());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "mopl-dlt-replay-cli");
        return new DefaultKafkaConsumerFactory<>(
                properties,
                new ByteArrayDeserializer(),
                new ByteArrayDeserializer()
        );
    }
}
