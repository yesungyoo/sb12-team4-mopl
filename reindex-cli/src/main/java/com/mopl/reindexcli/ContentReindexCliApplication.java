package com.mopl.reindexcli;

import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.search.service.ContentEmbeddingIndexer;
import com.mopl.content.search.service.ContentEmbeddingService;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.config.AiConfig;
import com.mopl.infrastructure.ai.openai.OpenAiEmbeddingClient;
import com.mopl.infrastructure.ai.openai.OpenAiRestClientFactory;
import com.mopl.infrastructure.config.QuerydslConfig;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 재색인에 필요한 Bean만 등록해 API 부수 기능을 띄우지 않는 CLI Context입니다. */
@SpringBootConfiguration
@EnableAutoConfiguration(
        exclude = {
                KafkaAutoConfiguration.class,
                TaskSchedulingAutoConfiguration.class
        },
        excludeName = "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
)
@EntityScan("com.mopl.core.domain")
@EnableJpaRepositories(basePackages = {
        "com.mopl.content.repository",
        "com.mopl.review.repository"
})
@EnableElasticsearchRepositories(basePackageClasses = ContentSearchRepository.class)
@Import({
        ContentSearchIndexer.class,
        ContentEmbeddingIndexer.class,
        ContentEmbeddingService.class,
        ContentReindexCliRunner.class,
        ReindexCliPersistenceSafetyConfiguration.class,
        AiAvailability.class,
        AiConfig.class,
        OpenAiEmbeddingClient.class,
        OpenAiRestClientFactory.class,
        QuerydslConfig.class
})
public class ContentReindexCliApplication {

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    static int execute(String[] args) {
        try {
            ReindexMode.from(new DefaultApplicationArguments(args));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return 2;
        }

        SpringApplication application = createApplication();
        try (ConfigurableApplicationContext context = application.run(args)) {
            return SpringApplication.exit(context);
        } catch (Exception exception) {
            System.err.println("재색인 CLI 기동 또는 인프라 처리에 실패했습니다.");
            exception.printStackTrace(System.err);
            return startupFailureExitCode(exception);
        }
    }

    static SpringApplication createApplication() {
        SpringApplication application = new SpringApplication(
                ContentReindexCliApplication.class
        );
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(new ReindexCliEnvironmentInitializer());
        return application;
    }

    static int startupFailureExitCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ReindexCliConfigurationException
                    || current instanceof ContentEmbeddingIndexer
                            .BackfillConfigurationException) {
                return 2;
            }
            current = current.getCause();
        }
        return 3;
    }
}
