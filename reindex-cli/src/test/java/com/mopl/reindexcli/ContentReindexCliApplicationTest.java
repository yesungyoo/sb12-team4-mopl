package com.mopl.reindexcli;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.search.kafka.ContentSearchSyncKafkaConsumer;
import com.mopl.notification.sse.SseHeartbeatScheduler;
import com.mopl.user.config.AdminInitializer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.kafka.annotation.EnableKafka;
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
                .containsEntry("hibernate.hbm2ddl.auto", "validate");
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
                .contains(KafkaAutoConfiguration.class);
        assertThat(ApplicationRunner.class
                .isAssignableFrom(ContentReindexCliRunner.class))
                .isTrue();
    }

    @Test
    void missingModeIsRejectedBeforeSpringContextStarts() {
        assertThat(ContentReindexCliApplication.execute(new String[0]))
                .isEqualTo(2);
    }

    @Test
    void startupValidationFailuresUseConfigurationExitCode() {
        assertThat(ContentReindexCliApplication.startupFailureExitCode(
                new IllegalStateException(
                        "mopl.elasticsearch.embedding-backfill.max-consecutive-es-failures 설정은 양수여야 합니다."
                )
        )).isEqualTo(2);
        assertThat(ContentReindexCliApplication.startupFailureExitCode(
                new IllegalStateException("Elasticsearch unavailable")
        )).isEqualTo(3);
    }
}
