package com.mopl.playlist.controller;

import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionNotFoundException;
import com.mopl.playlist.service.PlaylistSubscriptionService;
import com.mopl.auth.dto.AuthUser;
import com.mopl.core.common.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 컨트롤러 로직을 검증하는 테스트.
// JWT 인증 필터는 비활성화하고, SecurityContext에 인증 사용자를 직접 설정한다.
@WebMvcTest(PlaylistSubscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaylistSubscriptionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PlaylistSubscriptionService playlistSubscriptionService;
	private UUID playlistId;
	private UUID currentUserId;

	{
		playlistId = UUID.randomUUID();
		currentUserId = UUID.randomUUID();
	}

	@BeforeEach
	void setUpSecurityContext() {
		authenticateAs(currentUserId);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private void authenticateAs(UUID userId) {
		AuthUser authUser = new AuthUser(
			userId,
			"playlist-subscription-test@mopl.io",
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

	@Nested
	@DisplayName("구독")
	class Subscribe {

		@Test
		@DisplayName("구독 성공 시 204를 반환한다")
		void success() throws Exception {
			doNothing()
				.when(playlistSubscriptionService)
				.subscribe(currentUserId, playlistId);

			mockMvc.perform(
					post("/api/playlists/{playlistId}/subscription", playlistId)
				)
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트면 404를 반환한다")
		void playlistNotFound() throws Exception {
			doThrow(new PlaylistNotFoundException())
				.when(playlistSubscriptionService)
				.subscribe(currentUserId, playlistId);

			mockMvc.perform(
					post("/api/playlists/{playlistId}/subscription", playlistId)
				)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PLAYLIST_001"));
		}

		@Test
		@DisplayName("이미 구독한 플레이리스트면 400을 반환한다")
		void alreadySubscribed() throws Exception {
			doThrow(new PlaylistSubscriptionAlreadyExistsException())
				.when(playlistSubscriptionService)
				.subscribe(currentUserId, playlistId);

			mockMvc.perform(
					post("/api/playlists/{playlistId}/subscription", playlistId)
				)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PLAYLIST_005"));
		}
	}

	@Nested
	@DisplayName("구독 취소")
	class Unsubscribe {

		@Test
		@DisplayName("구독 취소 성공 시 204를 반환한다")
		void success() throws Exception {
			doNothing()
				.when(playlistSubscriptionService)
				.unsubscribe(currentUserId, playlistId);

			mockMvc.perform(
					delete("/api/playlists/{playlistId}/subscription", playlistId)
				)
				.andExpect(status().isNoContent());
		}
		@Test
		@DisplayName("구독하지 않은 플레이리스트면 404를 반환한다")
		void notSubscribed() throws Exception {
			doThrow(new PlaylistSubscriptionNotFoundException())
				.when(playlistSubscriptionService)
				.unsubscribe(currentUserId, playlistId);

			mockMvc.perform(
					delete("/api/playlists/{playlistId}/subscription", playlistId)
				)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PLAYLIST_006"));
		}
	}
}