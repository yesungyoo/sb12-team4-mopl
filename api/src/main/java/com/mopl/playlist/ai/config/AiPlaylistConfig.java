package com.mopl.playlist.ai.config;

import com.mopl.playlist.ai.tool.AiPlaylistTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// OpenAI API Key가 없는 환경에서도 애플리케이션이 정상 기동되도록 AI 설정을 조건부로 활성화
@ConditionalOnProperty(
	name = "mopl.ai.enabled",
	havingValue = "true"
)
public class AiPlaylistConfig {

	@Bean
	public ChatMemory aiPlaylistChatMemory() {
		return MessageWindowChatMemory.builder()
			.maxMessages(10)
			.build();
	}

	@Bean
	public ChatClient aiPlaylistChatClient(
		ChatClient.Builder chatClientBuilder,
		ChatMemory aiPlaylistChatMemory,
		AiPlaylistTools aiPlaylistTools
	) {
		return chatClientBuilder
			.defaultAdvisors(
				MessageChatMemoryAdvisor.builder(aiPlaylistChatMemory)
					.build()
			)
			.defaultTools(aiPlaylistTools)
			.build();
	}

	@Bean
	public ChatClient aiPlaylistTitleChatClient(
		ChatClient.Builder chatClientBuilder
	) {
		return chatClientBuilder.build();
	}
}