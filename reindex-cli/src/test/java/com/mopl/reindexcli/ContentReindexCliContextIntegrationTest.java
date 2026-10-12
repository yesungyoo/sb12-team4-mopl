package com.mopl.reindexcli;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.kafka.ContentSearchSyncKafkaConsumer;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentEmbeddingService;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.notification.sse.SseHeartbeatScheduler;
import com.mopl.user.config.AdminInitializer;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class ContentReindexCliContextIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mopl_reindex_cli_test")
            .withUsername("mopl")
            .withPassword("mopl")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse(
                    "docker.elastic.co/elasticsearch/elasticsearch:8.18.8"
            )
    )
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(5));

    @BeforeAll
    static void migrateIsolatedDatabaseBeforeStartingCliContext() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void cliContextInitializesJpaRepositoriesAndIndexersAgainstIsolatedServices() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ContentReindexCliApplication.class)
                .withPropertyValues(
                        "spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "spring.datasource.username=" + MYSQL.getUsername(),
                        "spring.datasource.password=" + MYSQL.getPassword(),
                        "spring.elasticsearch.uris="
                                + ELASTICSEARCH.getHttpHostAddress(),
                        "mopl.ai.enabled=false",
                        "ai.openai.api-key=",
                        "spring.ai.openai.api-key="
                )
                .withInitializer(new ReindexCliEnvironmentInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    EntityManagerFactory entityManagerFactory = context.getBean(
                            EntityManagerFactory.class
                    );

                    assertThat(context.getBean(ContentRepository.class)).isNotNull();
                    assertThat(context.getBean(ContentTagRepository.class)).isNotNull();
                    assertThat(context.getBean(ContentSearchRepository.class)).isNotNull();
                    assertThat(entityManagerFactory).isNotNull();
                    assertThat(context.getBean(ContentSearchIndexer.class)).isNotNull();
                    assertThat(context.getBean(ContentEmbeddingIndexer.class)).isNotNull();
                    assertThat(context.getBean(ContentEmbeddingService.class)).isNotNull();
                    assertThat(context.getBean(ContentReindexCliRunner.class)).isNotNull();

                    assertThat(context.getBeanFactory().containsBeanDefinition(
                            "flywayInitializer"
                    )).isFalse();
                    assertThat(context.getBeanFactory().containsBeanDefinition("flyway"))
                            .isFalse();
                    assertThat(context.getEnvironment().getProperty(
                            "spring.jpa.hibernate.ddl-auto"
                    )).isEqualTo("validate");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action"
                    )).isEqualTo("validate");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action"
                    )).isEqualTo("none");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.application.name"
                    )).isEqualTo("mopl-api");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type"
                    )).isEqualTo("CHAR");
                    assertThat(entityManagerFactory.getProperties()).containsEntry(
                            "hibernate.type.preferred_uuid_jdbc_type",
                            "CHAR"
                    );
                    assertThat(context.getEnvironment().getProperty(
                            "spring.datasource.url"
                    )).isEqualTo(MYSQL.getJdbcUrl());
                    assertThat(context.getEnvironment().getProperty(
                            "spring.datasource.username"
                    )).isEqualTo(MYSQL.getUsername());
                    assertThat(context.getEnvironment().getProperty(
                            "spring.datasource.password"
                    )).isEqualTo(MYSQL.getPassword());
                    assertThat(context.getEnvironment().getProperty(
                            "spring.elasticsearch.uris"
                    )).isEqualTo(ELASTICSEARCH.getHttpHostAddress());

                    assertThat(context.getBeanNamesForType(
                            ServletWebServerFactory.class
                    )).isEmpty();
                    assertThat(context.getBeanNamesForType(
                            ContentSearchSyncKafkaConsumer.class
                    )).isEmpty();
                    assertThat(context.getBeanNamesForType(
                            KafkaListenerEndpointRegistry.class
                    )).isEmpty();
                    assertThat(context.getBeanNamesForType(TaskScheduler.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(
                            SseHeartbeatScheduler.class
                    )).isEmpty();
                    assertThat(context.getBeanNamesForType(AdminInitializer.class))
                            .isEmpty();
                });
    }
}
