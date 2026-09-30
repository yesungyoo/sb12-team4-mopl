package com.mopl.playlist.ai.controller;

import com.mopl.auth.dto.AuthUser;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.playlist.ai.dto.AiPlaylistChatRequest;
import com.mopl.playlist.ai.dto.AiPlaylistChatResponse;
import com.mopl.playlist.ai.dto.AiPlaylistMessageResponse;
import com.mopl.playlist.ai.dto.AiPlaylistSessionResponse;
import com.mopl.playlist.ai.service.AiPlaylistChatService;
import com.mopl.playlist.ai.service.PlaylistAiMessageService;
import com.mopl.playlist.ai.service.PlaylistAiSessionService;
import com.mopl.playlist.ai.service.AiPlaylistRateLimiter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/playlists")
// OpenAI API Key가 있을 때만 AI Playlist API를 활성화
@ConditionalOnProperty(
	name = "mopl.ai.enabled",
	havingValue = "true"
)
public class AiPlaylistController {

	private final AiPlaylistChatService aiPlaylistChatService;
	private final PlaylistAiSessionService playlistAiSessionService;
	private final PlaylistAiMessageService playlistAiMessageService;
	private final AiPlaylistRateLimiter aiPlaylistRateLimiter;

	@PostMapping("/sessions")
	public UUID createSession(
		@AuthenticationPrincipal AuthUser authUser
	) {
		return playlistAiSessionService
			.createSession(authUser.userId(), null)
			.getId();
	}

	@GetMapping("/sessions")
	public CursorResponse<AiPlaylistSessionResponse> getSessions(
		@AuthenticationPrincipal AuthUser authUser,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) String idAfter,
		@RequestParam(defaultValue = "20") int limit,
		@RequestParam(defaultValue = "DESCENDING")
		String sortDirection
	) {
		return playlistAiSessionService.getSessions(
			authUser.userId(),
			cursor,
			idAfter,
			limit,
			sortDirection
		);
	}

	@GetMapping("/sessions/{sessionId}/messages")
	public CursorResponse<AiPlaylistMessageResponse> getMessages(
		@AuthenticationPrincipal AuthUser authUser,
		@PathVariable UUID sessionId,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) String idAfter,
		@RequestParam(defaultValue = "20") int limit,
		@RequestParam(defaultValue = "DESCENDING")
		String sortDirection
	) {
		playlistAiSessionService.getSession(
			sessionId,
			authUser.userId()
		);

		return playlistAiMessageService.getMessages(
			sessionId,
			cursor,
			idAfter,
			limit,
			sortDirection
		);
	}

	@PostMapping("/sessions/{sessionId}/messages")
	public AiPlaylistChatResponse chat(
		@AuthenticationPrincipal AuthUser authUser,
		@PathVariable UUID sessionId,
		@RequestBody @Valid AiPlaylistChatRequest request
	) {
		aiPlaylistRateLimiter.check(authUser.userId());

		String message = aiPlaylistChatService.chat(
			authUser.userId(),
			sessionId,
			request.message()
		);

		return new AiPlaylistChatResponse(
			sessionId,
			message
		);
	}
}