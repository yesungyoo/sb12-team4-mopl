package com.mopl.playlist.ai.service;

import com.mopl.core.common.enums.PlaylistAiMessageRole;
import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiPlaylistChatServiceTest {

	@Test
	@DisplayName("LLM 호출이 실패하면 사용자 메시지를 삭제하고 응답 후처리는 실행되지 않는다")
	void chatDeletesUserMessageWhenLlmFails() {
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		String userMessage = "비 오는 날 보기 좋은 영화 추천해줘";

		PlaylistAiSession session = mock(PlaylistAiSession.class);
		when(session.getTitle()).thenReturn(null);

		PlaylistAiSessionService sessionService =
			mock(PlaylistAiSessionService.class);

		PlaylistAiMessageService messageService =
			mock(PlaylistAiMessageService.class);

		when(sessionService.getSession(sessionId, userId))
			.thenReturn(session);

		PlaylistAiMessage savedUserMessage =
			mock(PlaylistAiMessage.class);

		UUID savedUserMessageId = UUID.randomUUID();

		when(savedUserMessage.getId())
			.thenReturn(savedUserMessageId);

		when(messageService.saveMessage(
			session,
			PlaylistAiMessageRole.USER,
			userMessage
		)).thenReturn(savedUserMessage);

		when(messageService.getRecentMessages(sessionId))
			.thenReturn(List.of());

		ChatMemory chatMemory = MessageWindowChatMemory.builder()
			.maxMessages(10)
			.build();

		ChatModel failingChatModel = mock(ChatModel.class);

		when(failingChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
			.thenThrow(new RuntimeException("LLM 호출 실패"));

		ChatClient chatClient = ChatClient.builder(failingChatModel)
			.defaultAdvisors(
				MessageChatMemoryAdvisor.builder(chatMemory)
					.build()
			)
			.build();

		ChatClient titleChatClient = ChatClient.builder(failingChatModel)
			.build();

		AiPlaylistChatService service = new AiPlaylistChatService(
			chatClient,
			titleChatClient,
			chatMemory,
			sessionService,
			messageService
		);

		assertThrows(
			RuntimeException.class,
			() -> service.chat(
				userId,
				sessionId,
				userMessage
			)
		);

		verify(messageService).saveMessage(
			session,
			PlaylistAiMessageRole.USER,
			userMessage
		);

		verify(messageService).deleteMessage(savedUserMessageId);

		verify(messageService, never()).saveMessage(
			eq(session),
			eq(PlaylistAiMessageRole.ASSISTANT),
			any()
		);

		verify(sessionService, never()).updateTitle(
			any(),
			anyString()
		);

		verify(sessionService, never()).touchSession(any());

		assertTrue(
			chatMemory.get(sessionId.toString()).isEmpty()
		);
	}
}