package com.mopl.playlist.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.dto.AuthUser;
import com.mopl.core.common.enums.PlaylistAiMessageRole;
import com.mopl.core.common.enums.UserRole;
import com.mopl.common.exception.playlist.PlaylistAiCandidateStoreUnavailableException;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import com.mopl.playlist.ai.dto.AiPlaylistChatRequest;
import com.mopl.playlist.ai.service.AiPlaylistChatService;
import com.mopl.playlist.ai.service.PlaylistAiMessageService;
import com.mopl.playlist.ai.service.PlaylistAiSessionService;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.playlist.ai.dto.AiPlaylistMessageResponse;
import com.mopl.playlist.ai.dto.AiPlaylistSessionResponse;
import com.mopl.common.exception.playlist.PlaylistAiRateLimitExceededException;
import com.mopl.playlist.ai.service.AiPlaylistRateLimiter;

import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
	controllers = AiPlaylistController.class,
	properties = "mopl.ai.enabled=true"
)
@AutoConfigureMockMvc(addFilters = false)
class AiPlaylistControllerTest {

	private UUID userId;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AiPlaylistChatService aiPlaylistChatService;

	@MockitoBean
	private PlaylistAiSessionService playlistAiSessionService;

	@MockitoBean
	private PlaylistAiMessageService playlistAiMessageService;

	@MockitoBean
	private AiPlaylistRateLimiter aiPlaylistRateLimiter;

	@BeforeEach
	void setUpAuthentication() {
		userId = UUID.randomUUID();

		AuthUser authUser = new AuthUser(
			userId,
			"test@mopl.com",
			UserRole.USER
		);

		UsernamePasswordAuthenticationToken authentication =
			new UsernamePasswordAuthenticationToken(
				authUser,
				null,
				List.of()
			);

		SecurityContextHolder.getContext()
			.setAuthentication(authentication);
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("메시지가 비어 있으면 400을 반환한다")
	void chatFailsWhenMessageIsBlank() throws Exception {
		UUID sessionId = UUID.randomUUID();

		AiPlaylistChatRequest request =
			new AiPlaylistChatRequest("");

		mockMvc.perform(post(
				"/api/ai/playlists/sessions/{sessionId}/messages",
				sessionId
			)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());

		verifyNoInteractions(aiPlaylistChatService);
	}

	@Test
	@DisplayName("AI 플레이리스트 대화 세션을 생성한다")
	void createSession() throws Exception {
		UUID sessionId = UUID.randomUUID();

		PlaylistAiSession session =
			mock(PlaylistAiSession.class);

		when(session.getId())
			.thenReturn(sessionId);

		when(playlistAiSessionService.createSession(userId, null))
			.thenReturn(session);

		mockMvc.perform(
				post("/api/ai/playlists/sessions")
			)
			.andExpect(status().isOk())
			.andExpect(
				content().string("\"" + sessionId + "\"")
			);

		verify(playlistAiSessionService)
			.createSession(userId, null);
	}

	@Test
	@DisplayName("AI 플레이리스트 대화 세션 목록을 커서 페이지네이션으로 조회한다")
	void getSessions() throws Exception {
		UUID sessionId = UUID.randomUUID();

		AiPlaylistSessionResponse session =
			new AiPlaylistSessionResponse(
				sessionId,
				"비 오는 날 영화",
				LocalDateTime.of(2026, 9, 29, 10, 0),
				LocalDateTime.of(2026, 9, 29, 11, 0)
			);

		CursorResponse<AiPlaylistSessionResponse> response =
			CursorResponse.of(
				List.of(session),
				"2026-09-29T11:00:00",
				sessionId.toString(),
				true,
				2L,
				"UPDATED_AT",
				"DESCENDING"
			);

		when(playlistAiSessionService.getSessions(
			userId,
			null,
			null,
			20,
			"DESCENDING"
		)).thenReturn(response);

		mockMvc.perform(
				get("/api/ai/playlists/sessions")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id")
				.value(sessionId.toString()))
			.andExpect(jsonPath("$.data[0].title")
				.value("비 오는 날 영화"))
			.andExpect(jsonPath("$.data[0].createdAt")
				.value("2026-09-29T10:00:00"))
			.andExpect(jsonPath("$.data[0].updatedAt")
				.value("2026-09-29T11:00:00"))
			.andExpect(jsonPath("$.nextCursor")
				.value("2026-09-29T11:00:00"))
			.andExpect(jsonPath("$.nextIdAfter")
				.value(sessionId.toString()))
			.andExpect(jsonPath("$.hasNext")
				.value(true))
			.andExpect(jsonPath("$.totalCount")
				.value(2))
			.andExpect(jsonPath("$.sortBy")
				.value("UPDATED_AT"))
			.andExpect(jsonPath("$.sortDirection")
				.value("DESCENDING"));

		verify(playlistAiSessionService).getSessions(
			userId,
			null,
			null,
			20,
			"DESCENDING"
		);
	}

	@Test
	@DisplayName("AI 플레이리스트 대화 메시지 목록을 커서 페이지네이션으로 조회한다")
	void getMessages() throws Exception {
		UUID sessionId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();

		AiPlaylistMessageResponse message =
			new AiPlaylistMessageResponse(
				messageId,
				PlaylistAiMessageRole.USER,
				"비 오는 날 보기 좋은 영화 추천해줘",
				LocalDateTime.of(2026, 9, 29, 12, 0)
			);

		CursorResponse<AiPlaylistMessageResponse> response =
			CursorResponse.of(
				List.of(message),
				"2026-09-29T12:00:00",
				messageId.toString(),
				true,
				2L,
				"CREATED_AT",
				"DESCENDING"
			);

		when(playlistAiMessageService.getMessages(
			sessionId,
			null,
			null,
			20,
			"DESCENDING"
		)).thenReturn(response);

		mockMvc.perform(
				get(
					"/api/ai/playlists/sessions/{sessionId}/messages",
					sessionId
				)
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id")
				.value(messageId.toString()))
			.andExpect(jsonPath("$.data[0].role")
				.value("USER"))
			.andExpect(jsonPath("$.data[0].content")
				.value("비 오는 날 보기 좋은 영화 추천해줘"))
			.andExpect(jsonPath("$.nextCursor")
				.value("2026-09-29T12:00:00"))
			.andExpect(jsonPath("$.nextIdAfter")
				.value(messageId.toString()))
			.andExpect(jsonPath("$.hasNext")
				.value(true))
			.andExpect(jsonPath("$.totalCount")
				.value(2))
			.andExpect(jsonPath("$.sortBy")
				.value("CREATED_AT"))
			.andExpect(jsonPath("$.sortDirection")
				.value("DESCENDING"));

		verify(playlistAiSessionService)
			.getSession(sessionId, userId);

		verify(playlistAiMessageService).getMessages(
			sessionId,
			null,
			null,
			20,
			"DESCENDING"
		);
	}

	@Test
	@DisplayName("AI 플레이리스트 대화 메시지를 전송한다")
	void chat() throws Exception {
		UUID sessionId = UUID.randomUUID();

		AiPlaylistChatRequest request =
			new AiPlaylistChatRequest(
				"비 오는 날 보기 좋은 영화 추천해줘"
			);

		when(aiPlaylistChatService.chat(
			userId,
			sessionId,
			request.message()
		)).thenReturn("비 오는 날 보기 좋은 영화를 추천해드릴게요.");

		mockMvc.perform(
				post(
					"/api/ai/playlists/sessions/{sessionId}/messages",
					sessionId
				)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request))
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId")
				.value(sessionId.toString()))
			.andExpect(jsonPath("$.message")
				.value("비 오는 날 보기 좋은 영화를 추천해드릴게요."));

		verify(aiPlaylistRateLimiter)
			.check(userId);

		verify(aiPlaylistChatService).chat(
			userId,
			sessionId,
			request.message()
		);
	}
	@Test
	@DisplayName("메시지가 1000자를 초과하면 400을 반환한다")
	void chatFailsWhenMessageExceedsMaxLength() throws Exception {
		UUID sessionId = UUID.randomUUID();

		AiPlaylistChatRequest request =
			new AiPlaylistChatRequest("a".repeat(1001));

		mockMvc.perform(post(
				"/api/ai/playlists/sessions/{sessionId}/messages",
				sessionId
			)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());

		verifyNoInteractions(aiPlaylistChatService);
	}

	@Test
	@DisplayName("AI 후보 저장소 장애 시 503을 반환한다")
	void chatReturnsServiceUnavailableWhenCandidateStoreIsUnavailable()
		throws Exception {

		UUID sessionId = UUID.randomUUID();

		AiPlaylistChatRequest request =
			new AiPlaylistChatRequest("비 오는 날 영화 추천해줘");

		when(aiPlaylistChatService.chat(
			eq(userId),
			eq(sessionId),
			eq(request.message())
		)).thenThrow(
			new PlaylistAiCandidateStoreUnavailableException()
		);

		mockMvc.perform(
				post(
					"/api/ai/playlists/sessions/{sessionId}/messages",
					sessionId
				)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request))
			)
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code")
				.value("PLAYLIST_009"));
	}

	@Test
	@DisplayName("AI 요청 제한을 초과하면 429와 Retry-After를 반환하고 AI 채팅을 호출하지 않는다")
	void chatReturnsTooManyRequestsWhenRateLimitIsExceeded()
		throws Exception {

		UUID sessionId = UUID.randomUUID();

		AiPlaylistChatRequest request =
			new AiPlaylistChatRequest("비 오는 날 보기 좋은 영화 추천해줘");

		doThrow(
			new PlaylistAiRateLimitExceededException(42)
		).when(aiPlaylistRateLimiter)
			.check(userId);

		mockMvc.perform(
				post(
					"/api/ai/playlists/sessions/{sessionId}/messages",
					sessionId
				)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request))
			)
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(
				HttpHeaders.RETRY_AFTER,
				"42"
			))
			.andExpect(jsonPath("$.code")
				.value("PLAYLIST_010"));

		verify(aiPlaylistRateLimiter)
			.check(userId);

		verifyNoInteractions(aiPlaylistChatService);
	}
}

