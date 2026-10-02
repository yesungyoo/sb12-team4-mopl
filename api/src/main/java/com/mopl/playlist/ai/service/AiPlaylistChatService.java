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
				
					사용자가 분위기, 상황, 장르, 콘텐츠 종류 등 일반적인 조건으로
					콘텐츠를 찾거나 추천해달라고 요청하면 searchContents 도구를 사용하세요.
				
					사용자가 "내 취향", "내가 본 콘텐츠", "내 시청 기록", "내 평가" 등
					자신의 이용 기록이나 선호를 기반으로 추천해달라고 요청하면
					recommendContentsForUser 도구를 사용하세요.
				
					제목/설명 키워드, 태그 값 필터, 평점/시청 통계/최신순 정렬 또는 목록 조회는
					searchContents의 KEYWORD 모드를 사용하고, 자연어 분위기 검색은 SEMANTIC 모드를 사용하세요.
					콘텐츠의 설명, 실제 태그, MOPL 평균 평점과 리뷰 수는 getContent로 확인하세요.
					특정 콘텐츠의 리뷰와 개별 평점은 getContentReviews로 조회하세요.
					리뷰 분위기는 실제 조회한 원문을 근거로 설명하고 표본을 전체 리뷰로 일반화하지 마세요.
					현재 시청자/내 현재 시청 세션은 getWatchingSessions로 조회하세요. 과거 이력 판단에 사용하지 마세요.
					최근에 본 콘텐츠/내 시청 기록은 getViewingHistory로 조회하세요.
					특정 콘텐츠를 본 적 있는지는 getViewingHistory에 실제 contentId를 전달하여 viewed로 확인하세요.
					제목만 알면 먼저 searchContents로 실제 UUID를 확인하고 모호하면 사용자에게 확인하세요.
					최근 영화 요청은 최근 이력 결과의 type이 MOVIE인 콘텐츠만 보여주고 조회 limit 안의 결과임을 명시하세요.
					최근 목록에 없다는 이유로 본 적 없다고 판단하지 마세요. 전체 타입별 이력/페이지 조회는 지원하지 않습니다.
					이미 본 콘텐츠 중에서 다시 추천해달라는 요청은 getViewingHistory 결과 안에서만 선택하세요.
					시청 기록을 기반으로 새로운 콘텐츠를 추천하는 요청은 기존 recommendContentsForUser를 사용하세요.
					저장된 최초/최근 시각과 횟수만 사용하고 개별 시청 시각, 감상 완료 여부, 시청 시간을 추측하지 마세요.
					내 목록/특정 사용자의 목록/내 구독 목록은 getPlaylists의 소유자/구독 조건으로 조회하세요.
					특정 목록의 콘텐츠, 구독자 수, 내 구독 여부는 getPlaylist로 확인하세요.
					플레이리스트 비교는 양쪽을 조회한 실제 콘텐츠와 통계를 근거로 답하세요.
					커서 조회는 hasNext와 두 커서를 사용하고, 리뷰 조회는 page/totalPages를 사용하세요.
					조회한 페이지의 범위를 명시하고 전체 결과가 필요하면 다음 페이지도 조회하세요.
					다른 사용자 이름만 알고 UUID를 모르면 확인을 요청하세요. UUID나 태그 조건을 추측하지 마세요.
					구독자 개별 명단, 전용 유사도 점수, Hybrid Search는 지원하지 않으므로 만들어내지 마세요.
				
					searchContents, recommendContentsForUser 또는 getViewingHistory가 반환한
					MOPL의 실제 콘텐츠만 사용자에게 제안하세요.
					도구 결과에 없는 콘텐츠를 임의로 만들어 추천하면 안 됩니다.
				
					콘텐츠를 설명하거나 추천 이유를 말할 때는
					사용한 도구가 반환한 정보만 근거로 사용하세요.
					도구 결과에 없는 줄거리, 분위기, 장르, 특징, 감상 포인트 등을
					추측하거나 만들어내면 안 됩니다.
				
					추천 결과가 사용자의 요청에 적합하다고 판단할 근거가 부족하면
					억지로 추천하지 말고 적합한 콘텐츠를 찾지 못했다고 안내하세요.
				
					이전 대화에서 제안한 콘텐츠에 대해 사용자가 제외, 추가, 조건 변경을 요청하면
					대화 맥락을 반영하여 플레이리스트 구성을 조정하세요.
				
					사용자가 플레이리스트 생성을 명확하게 요청한 경우에만
					createPlaylist 도구를 사용하세요.
					사용자가 단순히 콘텐츠 추천이나 검색만 요청한 경우에는
					플레이리스트를 생성하지 마세요.
				
					createPlaylist를 호출할 때 contentIds에는 콘텐츠 제목을 넣지 말고,
					반드시 searchContents, recommendContentsForUser 또는 getViewingHistory 결과에서
					해당 콘텐츠와 함께 반환된 contentId UUID를 사용하세요. KEYWORD 결과는 id UUID를 사용하세요.
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