package com.mopl.realtime.moderation.config;

import com.mopl.infrastructure.ai.config.AiProperties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
@EnableConfigurationProperties({ModerationProperties.class, MessageReviewProperties.class})
public class ModerationConfig {
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService moderationReviewExecutor(ModerationProperties properties) {
        return executor(properties, "moderation-review-");
    }
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService moderationLlmExecutor(ModerationProperties properties) {
        return executor(properties, "moderation-llm-");
    }
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService moderationMessageExecutor(MessageReviewProperties properties) {
        return new ThreadPoolExecutor(properties.workers(), properties.workers(), 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name("moderation-message-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledThreadPoolExecutor moderationMessageScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("moderation-message-deadline-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private ExecutorService executor(ModerationProperties properties, String name) {
        return new ThreadPoolExecutor(properties.workers(), properties.workers(), 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name(name, 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnProperty(name = "mopl.ai.enabled", havingValue = "true")
    public ChatClient moderationChatClient(ChatClient.Builder builder) {
        // Playlist의 모델 자동설정과 같은 Spring AI Builder를 사용하며 대화 메모리는 공유하지 않는다.
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "mopl.ai.enabled", havingValue = "true")
    public RestClientCustomizer moderationAiTimeouts(AiProperties properties) {
        return builder -> {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(properties.connectTimeout());
            factory.setReadTimeout(properties.readTimeout());
            builder.requestFactory(factory);
        };
    }
}
