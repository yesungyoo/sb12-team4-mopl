package com.mopl.playlist.ai.service;

import java.util.Map;
import java.util.UUID;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import com.mopl.core.common.enums.PlaylistAiMessageRole;
import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
// OpenAI API Key가 있을 때만 AI Playlist 채팅 서비스를 활성화
@ConditionalOnProperty(
	name = "mopl.ai.enabled",
	havingValue = "true"
)
public class AiPlaylistChatService {

	private final ChatClient aiPlaylistChatClient;
	private final ChatClient aiPlaylistTitleChatClient;
	private final ChatMemory aiPlaylistChatMemory;
	private final PlaylistAiSessionService playlistAiSessionService;
	private final PlaylistAiMessageService playlistAiMessageService;

	public AiPlaylistChatService(
		@Qualifier("aiPlaylistChatClient")
		ChatClient aiPlaylistChatClient,

		@Qualifier("aiPlaylistTitleChatClient")
		ChatClient aiPlaylistTitleChatClient,

		ChatMemory aiPlaylistChatMemory,
		PlaylistAiSessionService playlistAiSessionService,
		PlaylistAiMessageService playlistAiMessageService
	) {
		this.aiPlaylistChatClient = aiPlaylistChatClient;
		this.aiPlaylistTitleChatClient = aiPlaylistTitleChatClient;
		this.aiPlaylistChatMemory = aiPlaylistChatMemory;
		this.playlistAiSessionService = playlistAiSessionService;
		this.playlistAiMessageService = playlistAiMessageService;
	}

	public String chat(
		UUID currentUserId,
		UUID sessionId,
		String message
	) {
		PlaylistAiSession session =
			playlistAiSessionService.getSession(sessionId, currentUserId);

		String memoryConversationId = sessionId.toString();

		restoreChatMemory(memoryConversationId);

		PlaylistAiMessage userMessage =
			playlistAiMessageService.saveMessage(
				session,
				PlaylistAiMessageRole.USER,
				message
			);

		List<Message> memoryBeforeCall =
			List.copyOf(aiPlaylistChatMemory.get(memoryConversationId));

		String response;

		try {
			response = aiPlaylistChatClient.prompt()
				.system("""
            당신은 MOPL의 AI Playlist 챗봇입니다.
            사용자가 원하는 콘텐츠를 찾고, 대화를 통해 플레이리스트를 구성할 수 있도록 돕습니다.

            콘텐츠를 새로 찾거나 추천해야 할 때는 searchContents 도구를 사용하세요.
            searchContents가 반환한 MOPL의 실제 콘텐츠만 사용자에게 제안하세요.
            검색 결과에 없는 콘텐츠를 임의로 만들어 추천하면 안 됩니다.

            콘텐츠를 설명하거나 추천 이유를 말할 때는
            searchContents가 반환한 정보만 근거로 사용하세요.
            검색 결과에 없는 줄거리, 분위기, 장르, 특징, 감상 포인트 등을
            추측하거나 만들어내면 안 됩니다.

            검색된 콘텐츠가 사용자의 요청에 적합하다고 판단할 근거가 부족하면
            억지로 추천하지 말고 적합한 콘텐츠를 찾지 못했다고 안내하세요.

            이전 대화에서 제안한 콘텐츠에 대해 사용자가 제외, 추가, 조건 변경을 요청하면
            대화 맥락을 반영하여 플레이리스트 구성을 조정하세요.

            사용자가 플레이리스트 생성을 명확하게 요청한 경우에만 createPlaylist 도구를 사용하세요.
            사용자가 단순히 콘텐츠 추천이나 검색만 요청한 경우에는 플레이리스트를 생성하지 마세요.

            createPlaylist를 호출할 때 contentIds에는 콘텐츠 제목을 넣지 말고,
            반드시 searchContents 결과에서 해당 콘텐츠와 함께 반환된 contentId UUID를 사용하세요.
            """)
				.advisors(advisor -> advisor.param(
					ChatMemory.CONVERSATION_ID,
					memoryConversationId
				))
				.toolContext(Map.of(
					"currentUserId", currentUserId,
					"sessionId", sessionId.toString()
				))
				.user(message)
				.call()
				.content();
		} catch (RuntimeException e) {
			playlistAiMessageService.deleteMessage(userMessage.getId());

			aiPlaylistChatMemory.clear(memoryConversationId);

			if (!memoryBeforeCall.isEmpty()) {
				aiPlaylistChatMemory.add(
					memoryConversationId,
					memoryBeforeCall
				);
			}

			throw e;
		}

		playlistAiMessageService.saveMessage(
			session,
			PlaylistAiMessageRole.ASSISTANT,
			response
		);

		if (session.getTitle() == null) {
			try {
				String title = generateSessionTitle(message);
				playlistAiSessionService.updateTitle(session, title);
			} catch (Exception e) {
				log.warn(
					"AI Playlist 세션 제목 생성에 실패했습니다. sessionId={}",
					sessionId,
					e
				);
			}
		}

		playlistAiSessionService.touchSession(sessionId);

		return response;
	}

	private String generateSessionTitle(String firstMessage) {
		String title = aiPlaylistTitleChatClient.prompt()
			.system("""
            사용자의 첫 메시지를 바탕으로 채팅방 제목을 생성하세요.
            제목은 15자 이내의 짧은 한국어 문구로 작성하세요.
            따옴표나 설명 없이 제목만 반환하세요.
            """)
			.user(firstMessage)
			.call()
			.content();

		if (title == null || title.isBlank()) {
			return "새로운 대화";
		}

		title = title.trim();

		return title.length() > 15
			? title.substring(0, 15)
			: title;
	}

	private void restoreChatMemory(String conversationId) {
		if (!aiPlaylistChatMemory.get(conversationId).isEmpty()) {
			return;
		}

		playlistAiMessageService
			.getRecentMessages(UUID.fromString(conversationId))
			.forEach(message -> {
				switch (message.getRole()) {
					case USER -> aiPlaylistChatMemory.add(
						conversationId,
						new UserMessage(message.getContent())
					);
					case ASSISTANT -> aiPlaylistChatMemory.add(
						conversationId,
						new AssistantMessage(message.getContent())
					);
				}
			});
	}
}