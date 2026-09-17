package com.mopl.playlist.controller;

import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionNotFoundException;
import com.mopl.playlist.service.PlaylistSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Security(JWT) 도입으로 인한 필터체인 영향을 받지 않도록 처리.
@WebMvcTest(PlaylistSubscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaylistSubscriptionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PlaylistSubscriptionService playlistSubscriptionService;

	private UUID playlistId;
	private UUID requesterId;

	{
		playlistId = UUID.randomUUID();
		requesterId = UUID.randomUUID();
	}

	@Nested
	@DisplayName("구독")
	class Subscribe {

		@Test
		@DisplayName("구독 성공 시 204를 반환한다")
		void success() throws Exception {
			doNothing().when(playlistSubscriptionService).subscribe(requesterId, playlistId);

			mockMvc.perform(post("/playlists/{playlistId}/subscription", playlistId)
							.param("requesterId", requesterId.toString()))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트면 404를 반환한다")
		void playlistNotFound() throws Exception {
			doThrow(new PlaylistNotFoundException())
					.when(playlistSubscriptionService).subscribe(requesterId, playlistId);

			mockMvc.perform(post("/playlists/{playlistId}/subscription", playlistId)
							.param("requesterId", requesterId.toString()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PLAYLIST_001"));
		}

		@Test
		@DisplayName("이미 구독한 플레이리스트면 400을 반환한다")
		void alreadySubscribed() throws Exception {
			doThrow(new PlaylistSubscriptionAlreadyExistsException())
					.when(playlistSubscriptionService).subscribe(requesterId, playlistId);

			mockMvc.perform(post("/playlists/{playlistId}/subscription", playlistId)
							.param("requesterId", requesterId.toString()))
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
			doNothing().when(playlistSubscriptionService).unsubscribe(requesterId, playlistId);

			mockMvc.perform(delete("/playlists/{playlistId}/subscription", playlistId)
							.param("requesterId", requesterId.toString()))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("구독하지 않은 플레이리스트면 404를 반환한다")
		void notSubscribed() throws Exception {
			doThrow(new PlaylistSubscriptionNotFoundException())
					.when(playlistSubscriptionService).unsubscribe(requesterId, playlistId);

			mockMvc.perform(delete("/playlists/{playlistId}/subscription", playlistId)
							.param("requesterId", requesterId.toString()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PLAYLIST_006"));
		}
	}
}