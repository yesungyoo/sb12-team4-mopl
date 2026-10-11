package com.mopl.reindexcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.content.search.kafka.ContentSearchSyncKafkaConsumer;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentEmbeddingService;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.notification.sse.SseHeartbeatScheduler;
import com.mopl.user.config.AdminInitializer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

class ContentReindexCliApplicationTest {

    @Test
    void flywayIsExcludedAndHibernateSchemaActionIsValidationOnly() {
        EnableAutoConfiguration autoConfiguration = ContentReindexCliApplication.class
                .getAnnotation(EnableAutoConfiguration.class);
        assertThat(autoConfiguration.excludeName())
                .contains("org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");

        Import imports = ContentReindexCliApplication.class
                .getAnnotation(Import.class);
        assertThat(imports.value())
                .contains(ReindexCliPersistenceSafetyConfiguration.class);

        HibernatePropertiesCustomizer customizer =
                new ReindexCliPersistenceSafetyConfiguration()
                        .schemaValidationOnly();
        Map<String, Object> hibernateProperties = new HashMap<>();
        customizer.customize(hibernateProperties);

        assertThat(hibernateProperties)
                .containsEntry("hibernate.hbm2ddl.auto", "validate")
                .containsEntry(
                        "jakarta.persistence.schema-generation.database.action",
                        "validate"
                )
                .containsEntry(
                        "jakarta.persistence.schema-generation.scripts.action",
                        "none"
                );
    }

    @Test
    void hibernatePerformsJpaSchemaValidationWithoutCreatingSchema() {
        HibernatePropertiesCustomizer customizer =
                new ReindexCliPersistenceSafetyConfiguration()
                        .schemaValidationOnly();
        Map<String, Object> hibernateProperties = new HashMap<>();
        customizer.customize(hibernateProperties);

        // JPA 스키마 검증 설정 자체가 동작하는지 확인하려고 Hibernate 전용 설정은 제외합니다.
        hibernateProperties.remove("hibernate.hbm2ddl.auto");
        hibernateProperties.put("hibernate.connection.driver_class", "org.h2.Driver");
        hibernateProperties.put(
                "hibernate.connection.url",
                "jdbc:h2:mem:reindex_cli_schema_validation"
        );
        hibernateProperties.put("hibernate.connection.username", "sa");
        hibernateProperties.put("hibernate.connection.password", "");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(hibernateProperties)
                .build();
        try {
            assertThatThrownBy(() -> new MetadataSources(registry)
                    .addAnnotatedClass(SchemaValidationProbe.class)
                    .buildMetadata()
                    .buildSessionFactory())
                    .isInstanceOf(SchemaManagementException.class)
                    .hasMessageContaining("missing table");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void applicationRunsOnlyTheStandaloneCliSourceWithoutAWebServer() {
        SpringApplication application = ContentReindexCliApplication.createApplication();

        assertThat(application.getAllSources())
                .containsOnly(ContentReindexCliApplication.class);
        assertThat(application.getWebApplicationType())
                .isEqualTo(WebApplicationType.NONE);
    }

    @Test
    void contextHasNoApiComponentScanOrSideEffectConfigurations() {
        assertThat(ContentReindexCliApplication.class
                .isAnnotationPresent(ComponentScan.class))
                .isFalse();
        assertThat(ContentReindexCliApplication.class
                .isAnnotationPresent(EnableKafka.class))
                .isFalse();
        assertThat(ContentReindexCliApplication.class
                .isAnnotationPresent(EnableScheduling.class))
                .isFalse();
        assertThat(ContentReindexCliApplication.createApplication()
                .getWebApplicationType())
                .isEqualTo(WebApplicationType.NONE);

        Import imports = ContentReindexCliApplication.class
                .getAnnotation(Import.class);
        assertThat(imports.value())
                .doesNotContain(
                        ContentSearchSyncKafkaConsumer.class,
                        SseHeartbeatScheduler.class,
                        AdminInitializer.class
                );
        EnableAutoConfiguration autoConfiguration = ContentReindexCliApplication.class
                .getAnnotation(EnableAutoConfiguration.class);
        assertThat(autoConfiguration.exclude())
                .contains(
                        KafkaAutoConfiguration.class,
                        TaskSchedulingAutoConfiguration.class
                );
        assertThat(ApplicationRunner.class
                .isAssignableFrom(ContentReindexCliRunner.class))
                .isTrue();
    }

    @Test
    void cliContextStartsWithOnlyRequiredBeansAndNoExternalSideEffects() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContentReindexCliApplication.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:reindex-cli-context",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.elasticsearch.uris=http://127.0.0.1:1",
                        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                        "mopl.ai.enabled=false"
                )
                .withInitializer(context -> {
                    new ReindexCliEnvironmentInitializer().initialize(context);
                    context.addBeanFactoryPostProcessor(beanFactory -> {
                        for (String beanName : beanFactory.getBeanDefinitionNames()) {
                            beanFactory.getBeanDefinition(beanName).setLazyInit(true);
                        }
                    });
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(
                            ContentSearchIndexer.class, false, false
                    )).hasSize(1);

                    assertThat(context.getBeanNamesForType(
                            ContentEmbeddingIndexer.class, false, false
                    )).hasSize(1);

                    assertThat(context.getBeanNamesForType(
                            ContentEmbeddingService.class, false, false
                    )).hasSize(1);

                    assertThat(context.getBeanNamesForType(
                            ContentReindexCliRunner.class, false, false
                    )).hasSize(1);
                    var sourceContext = context.getSourceApplicationContext();
                    var environment = sourceContext.getEnvironment();
                    var beanFactory = sourceContext.getBeanFactory();

                    assertThat(beanFactory.containsBeanDefinition("contentRepository"))
                            .isTrue();
                    assertThat(beanFactory.containsBeanDefinition("contentTagRepository"))
                            .isTrue();
                    assertThat(beanFactory.containsBeanDefinition("contentSearchRepository"))
                            .isTrue();

                    assertThat(environment.getProperty("spring.ai.model.chat"))
                            .isEqualTo("none");
                    assertThat(environment.getProperty("spring.ai.model.embedding"))
                            .isEqualTo("none");
                    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class))
                            .isFalse();
                    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                            .isEqualTo("validate");
                    assertThat(environment.getProperty(
                            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action"
                    )).isEqualTo("validate");
                    assertThat(environment.getProperty(
                            "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action"
                    )).isEqualTo("none");
                    assertThat(environment.getProperty("spring.jpa.generate-ddl", Boolean.class))
                            .isFalse();
                    assertThat(environment.getProperty("spring.sql.init.mode"))
                            .isEqualTo("never");
                    assertThat(environment.getActiveProfiles())
                            .doesNotContain("local");

                    assertThat(beanFactory.getBeanNamesForType(
                            ServletWebServerFactory.class,
                            true,
                            false
                    )).isEmpty();
                    assertThat(beanFactory.getBeanNamesForType(
                            ContentSearchSyncKafkaConsumer.class,
                            true,
                            false
                    )).isEmpty();
                    assertThat(beanFactory.getBeanNamesForType(
                            KafkaListenerEndpointRegistry.class,
                            true,
                            false
                    )).isEmpty();
                    assertThat(beanFactory.getBeanNamesForType(
                            TaskScheduler.class,
                            true,
                            false
                    )).isEmpty();
                    assertThat(beanFactory.getBeanNamesForType(
                            SseHeartbeatScheduler.class,
                            true,
                            false
                    )).isEmpty();
                    assertThat(beanFactory.getBeanNamesForType(
                            AdminInitializer.class,
                            true,
                            false
                    )).isEmpty();
                    assertThat(beanFactory.containsBeanDefinition("flywayInitializer"))
                            .isFalse();
                    assertThat(beanFactory.containsBeanDefinition("flyway"))
                            .isFalse();
                });
    }

    @Test
    void cliConfigurationCanUseExistingEnvironmentSettingsWithoutLocalProfile() {
        MockEnvironment environment = new MockEnvironment()
                        .withProperty("DB_HOST", "db.example")
                        .withProperty("DB_PORT", "3306")
                        .withProperty("DB_NAME", "mopl")
                        .withProperty("DB_USERNAME", "mopl-user")
                        .withProperty("DB_PASSWORD", "")
                        .withProperty("ELASTICSEARCH_URL", "https://es.example")
                        .withProperty("OPENAI_API_KEY", "");
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);

        new ReindexCliEnvironmentInitializer().initialize(context);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://db.example:3306/mopl");
        assertThat(environment.getProperty("spring.datasource.username"))
                .isEqualTo("mopl-user");
        assertThat(environment.getProperty("spring.elasticsearch.uris"))
                .isEqualTo("https://es.example");
        assertThat(environment.getProperty("ai.openai.api-key")).isEmpty();
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void missingModeIsRejectedBeforeSpringContextStarts() {
        assertThat(ContentReindexCliApplication.execute(new String[0]))
                .isEqualTo(2);
    }

    @Test
    void startupValidationFailuresUseConfigurationExitCode() {
        assertThat(ContentReindexCliApplication.startupFailureExitCode(
                new ContentEmbeddingIndexer.BackfillConfigurationException(
                        "임계치가 잘못되었습니다."
                )
        )).isEqualTo(2);
        assertThat(ContentReindexCliApplication.startupFailureExitCode(
                new IllegalStateException(
                        "설정 실패 외 일반 오류",
                        new ReindexCliConfigurationException("DB 설정 누락")
                )
        )).isEqualTo(2);
        assertThat(ContentReindexCliApplication.startupFailureExitCode(
                new IllegalStateException("Elasticsearch unavailable")
        )).isEqualTo(3);
    }

    @Entity
    @Table(name = "reindex_cli_validation_probe")
    public static class SchemaValidationProbe {

        @Id
        private Long id;
    }
}
