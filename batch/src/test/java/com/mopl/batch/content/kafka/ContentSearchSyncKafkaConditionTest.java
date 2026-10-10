package com.mopl.batch.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mopl.batch.content.mapper.ContentBulkMapper;
import com.mopl.batch.content.repository.ContentBulkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ContentSearchSyncKafkaConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues(
                            "batch.content-search-sync."
                                    + "kafka-completion-timeout-ms=100"
                    )
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void createsAfterCommitPublisherWithoutKafkaConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(
                    ContentSearchSyncAfterCommitPublisher.class
            );
            assertThat(context).doesNotHaveBean(
                    ContentSearchSyncKafkaProducer.class
            );
            assertThat(context).hasSingleBean(
                    ContentBulkRepository.class
            );
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ContentSearchSyncAfterCommitPublisher.class,
            ContentSearchSyncKafkaProducer.class,
            ContentBulkRepository.class
    })
    static class TestConfiguration {

        @Bean
        ContentBulkMapper contentBulkMapper() {
            return mock(ContentBulkMapper.class);
        }
    }
}
