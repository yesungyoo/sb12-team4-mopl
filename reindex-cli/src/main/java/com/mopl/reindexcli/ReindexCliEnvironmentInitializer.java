package com.mopl.reindexcli;

import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/** CLI 전용 필수 접속 설정과 안전한 자동 설정을 구성합니다. */
public class ReindexCliEnvironmentInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String PROPERTY_SOURCE_NAME = "reindexCliSafetyAndConnections";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        Map<String, Object> properties = new HashMap<>();

        properties.put("spring.flyway.enabled", false);
        properties.put("spring.liquibase.enabled", false);
        properties.put("spring.sql.init.mode", "never");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.generate-ddl", false);
        properties.put("spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "validate");
        properties.put("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action", "none");
        properties.put("spring.ai.model.chat", "none");
        properties.put("spring.ai.model.embedding", "none");
        properties.put("spring.ai.model.image", "none");
        properties.put("spring.ai.model.moderation", "none");
        properties.put("spring.ai.model.audio.speech", "none");
        properties.put("spring.ai.model.audio.transcription", "none");

        configureDatabase(environment, properties);
        configureElasticsearch(environment, properties);

        String kafkaBootstrapServers = firstConfiguredValue(
                environment,
                "spring.kafka.bootstrap-servers",
                "KAFKA_BOOTSTRAP_SERVERS"
        );
        if (StringUtils.hasText(kafkaBootstrapServers)) {
            properties.put("spring.kafka.bootstrap-servers", kafkaBootstrapServers);
        }

        String apiKey = firstConfiguredValue(
                environment,
                "ai.openai.api-key",
                "OPENAI_API_KEY"
        );
        properties.put("ai.openai.api-key", apiKey == null ? "" : apiKey);

        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, properties)
        );
    }

    private void configureDatabase(
            ConfigurableEnvironment environment,
            Map<String, Object> properties
    ) {
        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            String host = requiredValue(environment, "DB_HOST");
            String port = requiredValue(environment, "DB_PORT");
            String database = requiredValue(environment, "DB_NAME");
            url = "jdbc:mysql://" + host + ":" + port + "/" + database;
        }
        properties.put("spring.datasource.url", url);

        properties.put(
                "spring.datasource.username",
                firstRequiredValue(environment, "spring.datasource.username", "DB_USERNAME")
        );
        properties.put(
                "spring.datasource.password",
                firstPresentValue(environment, "spring.datasource.password", "DB_PASSWORD")
        );
    }

    private void configureElasticsearch(
            ConfigurableEnvironment environment,
            Map<String, Object> properties
    ) {
        String uri = firstConfiguredValue(
                environment,
                "spring.elasticsearch.uris",
                "ELASTICSEARCH_URL"
        );
        if (!StringUtils.hasText(uri)) {
            throw new ReindexCliConfigurationException(
                    "Elasticsearch 접속 설정이 필요합니다. spring.elasticsearch.uris 또는 ELASTICSEARCH_URL을 설정하세요."
            );
        }
        properties.put("spring.elasticsearch.uris", uri);
    }

    private String firstRequiredValue(
            ConfigurableEnvironment environment,
            String primaryName,
            String fallbackName
    ) {
        String value = firstConfiguredValue(environment, primaryName, fallbackName);
        if (!StringUtils.hasText(value)) {
            throw new ReindexCliConfigurationException(
                    "필수 재색인 CLI 설정이 없습니다: " + primaryName + " 또는 " + fallbackName
            );
        }
        return value;
    }

    private String requiredValue(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        if (!StringUtils.hasText(value)) {
            throw new ReindexCliConfigurationException(
                    "필수 재색인 CLI 설정이 없습니다: " + name
            );
        }
        return value;
    }

    private String firstPresentValue(
            ConfigurableEnvironment environment,
            String primaryName,
            String fallbackName
    ) {
        String value = environment.getProperty(primaryName);
        if (value != null) {
            return value;
        }
        value = environment.getProperty(fallbackName);
        if (value != null) {
            return value;
        }
        throw new ReindexCliConfigurationException(
                "필수 재색인 CLI 설정이 없습니다: " + primaryName + " 또는 " + fallbackName
        );
    }

    private String firstConfiguredValue(
            ConfigurableEnvironment environment,
            String primaryName,
            String fallbackName
    ) {
        String value = environment.getProperty(primaryName);
        if (StringUtils.hasText(value)) {
            return value;
        }
        return environment.getProperty(fallbackName);
    }
}
