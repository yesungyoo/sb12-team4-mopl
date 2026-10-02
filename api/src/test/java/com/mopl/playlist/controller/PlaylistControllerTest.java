package com.mopl.playlist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistAccessDeniedException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
import com.mopl.auth.dto.AuthUser;
import com.mopl.core.common.enums.UserRole;
import com.mopl.playlist.dto.UserSummary;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.playlist.PlaylistContentAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistContentNotFoundException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 컨트롤러 로직을 검증하는 테스트.
// JWT 인증 필터는 비활성화하고, SecurityContext에 인증 사용자를 직접 설정한다.
@WebMvcTest(PlaylistController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaylistControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private PlaylistService playlistService;

	@BeforeEach
	void setUpSecurityContext() {
		authenticateAs(ownerId);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private void authenticateAs(UUID userId) {
		AuthUser authUser = new AuthUser(
			userId,
			"playlist-test@mopl.io",
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

	private UUID playlistId;
	private UUID ownerId;
	private PlaylistResponse sampleResponse;

	{
		playlistId = UUID.randomUUID();
		ownerId = UUID.randomUUID();
		sampleResponse = new PlaylistResponse(
			playlistId,
			new UserSummary(ownerId, "길동", "http://image.url"),
			"제목",
			"설명",
			LocalDateTime.now(),
			0L,
			false,
			Collections.emptyList()
		);
	}

	@Nested
	@DisplayName("단건 조회")
	class GetPlaylist {

		@Test
		@DisplayName("존재하는 플레이리스트를 조회하면 200과 본문을 반환한다")
		void success() throws Exception {
			when(playlistService.getPlaylist(playlistId, ownerId))
				.thenReturn(sampleResponse);

			mockMvc.perform(get("/api/playlists/{playlistId}", playlistId))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(playlistId.toString()))
					.andExpect(jsonPath("$.title").value("제목"));
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 조회하면 404와 오류 코드를 반환한다")
		void notFound() throws Exception {
			when(playlistService.getPlaylist(playlistId, ownerId))
				.thenThrow(new PlaylistNotFoundException());

			mockMvc.perform(get("/api/playlists/{playlistId}", playlistId))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PLAYLIST_001"));
		}
	}

	@Nested
	@DisplayName("목록 조회")
	class GetPlaylists {

		@Test
		@DisplayName("목록 조회 성공 시 데이터와 페이지 정보를 반환한다")
		void success() throws Exception {
			CursorResponse<PlaylistResponse> response = CursorResponse.of(
				List.of(sampleResponse),
				null,
				null,
				false,
				1L,
				"updatedAt",
				"DESCENDING"
			);

			when(playlistService.getPlaylists(
				any(),
				any(),
				eq(20),
				eq("updatedAt"),
				eq("DESCENDING"),
				eq(ownerId),
				any(),
				any(),
				any()
			)).thenReturn(response);



			mockMvc.perform(get("/api/playlists")
							.param("limit", "20")
							.param("sortBy", "updatedAt")
							.param("sortDirection", "DESCENDING"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data[0].id").value(playlistId.toString()))
					.andExpect(jsonPath("$.hasNext").value(false))
					.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("limit 파라미터가 없으면 400을 반환한다")
		void missingLimit_badRequest() throws Exception {
			mockMvc.perform(get("/api/playlists")
							.param("sortBy", "updatedAt")
							.param("sortDirection", "DESCENDING"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("subscriberIdEqual 파라미터를 서비스에 전달한다")
		void success_withSubscriberIdEqual() throws Exception {
			UUID subscriberIdEqual = UUID.randomUUID();
			CursorResponse<PlaylistResponse> response = CursorResponse.of(
				List.of(sampleResponse),
				null,
				null,
				false,
				1L,
				"updatedAt",
				"DESCENDING"
			);

			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), eq(subscriberIdEqual), any(), any()))
				.thenReturn(response);


			mockMvc.perform(get("/api/playlists")
							.param("limit", "20")
							.param("sortBy", "updatedAt")
							.param("sortDirection", "DESCENDING")
							.param("subscriberIdEqual", subscriberIdEqual.toString()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("ownerIdEqual 파라미터를 서비스에 전달한다")
		void success_withOwnerIdEqual() throws Exception {
			UUID ownerIdEqual = UUID.randomUUID();
			CursorResponse<PlaylistResponse> response = CursorResponse.of(
				List.of(sampleResponse),
				null,
				null,
				false,
				1L,
				"updatedAt",
				"DESCENDING"
			);
			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), any(), eq(ownerIdEqual), any()))
				.thenReturn(response);

			mockMvc.perform(get("/api/playlists")
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING")
					.param("ownerIdEqual", ownerIdEqual.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("ownerIdEqual이 UUID 형식이 아니면 400을 반환한다")
		void invalidOwnerIdEqualType_badRequest() throws Exception {
			mockMvc.perform(get("/api/playlists")
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING")
					.param("ownerIdEqual", "invalid-uuid"))
				.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("keywordLike 파라미터를 서비스에 전달한다")
		void success_withKeywordLike() throws Exception {
			String keywordLike = "비 오는 날";
			CursorResponse<PlaylistResponse> response = CursorResponse.of(
				List.of(sampleResponse),
				null,
				null,
				false,
				1L,
				"updatedAt",
				"DESCENDING"
			);
			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), any(), any(), eq(keywordLike)))
				.thenReturn(response);

			mockMvc.perform(get("/api/playlists")
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING")
					.param("keywordLike", keywordLike))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("잘못된 cursor 형식이면 400과 오류 코드를 반환한다")
		void invalidCursor_badRequest() throws Exception {
			UUID idAfter = UUID.randomUUID();

			when(playlistService.getPlaylists(
				eq("invalid-cursor"),
				eq(idAfter),
				eq(20),
				eq("updatedAt"),
				eq("DESCENDING"),
				any(),
				any(),
				any(),
				any()
			)).thenThrow(
				new MoplException(CommonErrorCode.INVALID_INPUT_VALUE)
			);

			mockMvc.perform(get("/api/playlists")
					.param("cursor", "invalid-cursor")
					.param("idAfter", idAfter.toString())
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_001"));
		}
	}

	@Nested
	@DisplayName("생성")
	class CreatePlaylist {

		@Test
		@DisplayName("생성 성공 시 201과 Location 헤더를 반환한다")
		void success() throws Exception {
			PlaylistCreateRequest request = new PlaylistCreateRequest("제목", "설명");
			when(playlistService.createPlaylist(eq(ownerId), any(PlaylistCreateRequest.class)))
					.thenReturn(sampleResponse);

			mockMvc.perform(post("/api/playlists")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", "/playlists/" + playlistId));
		}

		@Test
		@DisplayName("빈 제목으로 생성 요청하면 400을 반환한다")
		void blankTitle_badRequest() throws Exception {
			PlaylistCreateRequest request = new PlaylistCreateRequest("", "설명");

			mockMvc.perform(post("/api/playlists")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isBadRequest());
		}
	}

	@Nested
	@DisplayName("수정")
	class UpdatePlaylist {

		@Test
		@DisplayName("수정 성공 시 200을 반환한다")
		void success() throws Exception {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("새 제목", null);
			when(playlistService.updatePlaylist(eq(ownerId), eq(playlistId), any(PlaylistUpdateRequest.class)))
					.thenReturn(sampleResponse);

			mockMvc.perform(patch("/api/playlists/{playlistId}", playlistId)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(playlistId.toString()));
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID otherUserId = UUID.randomUUID();
			authenticateAs(otherUserId);

			PlaylistUpdateRequest request =
				new PlaylistUpdateRequest("새 제목", null);

			when(playlistService.updatePlaylist(
				eq(otherUserId),
				eq(playlistId),
				any(PlaylistUpdateRequest.class)
			)).thenThrow(new PlaylistAccessDeniedException());

			mockMvc.perform(patch("/api/playlists/{playlistId}", playlistId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PLAYLIST_002"));
		}

		@Test
		@DisplayName("공백 제목으로 수정하면 400을 반환한다")
		void blankTitle_badRequest() throws Exception {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("   ", null);
			when(playlistService.updatePlaylist(eq(ownerId), eq(playlistId), any(PlaylistUpdateRequest.class)))
					.thenThrow(new MoplException(CommonErrorCode.INVALID_INPUT_VALUE));

			mockMvc.perform(patch("/api/playlists/{playlistId}", playlistId)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isBadRequest());
		}
	}

	@Nested
	@DisplayName("삭제")
	class DeletePlaylist {

		@Test
		@DisplayName("삭제 성공 시 204를 반환한다")
		void success() throws Exception {
			doNothing().when(playlistService).deletePlaylist(ownerId, playlistId);

			mockMvc.perform(delete("/api/playlists/{playlistId}", playlistId))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID otherUserId = UUID.randomUUID();
			authenticateAs(otherUserId);

			doThrow(new PlaylistAccessDeniedException())
				.when(playlistService)
				.deletePlaylist(otherUserId, playlistId);

			mockMvc.perform(delete("/api/playlists/{playlistId}", playlistId))
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 삭제하면 404를 반환한다")
		void notFound() throws Exception {
			doThrow(new PlaylistNotFoundException())
					.when(playlistService).deletePlaylist(ownerId, playlistId);

			mockMvc.perform(delete("/api/playlists/{playlistId}", playlistId))
					.andExpect(status().isNotFound());
		}
	}

	@Nested
	@DisplayName("콘텐츠 추가")
	class AddContentToPlaylist {

		@Test
		@DisplayName("콘텐츠 추가 성공 시 204를 반환한다")
		void success() throws Exception {
			UUID contentId = UUID.randomUUID();
			doNothing().when(playlistService).addContentToPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(post("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID contentId = UUID.randomUUID();
			UUID otherUserId = UUID.randomUUID();

			authenticateAs(otherUserId);

			doThrow(new PlaylistAccessDeniedException())
				.when(playlistService)
				.addContentToPlaylist(otherUserId, playlistId, contentId);

			mockMvc.perform(
					post("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
				)
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("존재하지 않는 콘텐츠면 404를 반환한다")
		void contentNotFound() throws Exception {
			UUID contentId = UUID.randomUUID();
			doThrow(new ContentNotFoundException())
					.when(playlistService).addContentToPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(post("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("이미 추가된 콘텐츠면 400을 반환한다")
		void alreadyExists() throws Exception {
			UUID contentId = UUID.randomUUID();
			doThrow(new PlaylistContentAlreadyExistsException())
					.when(playlistService).addContentToPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(post("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("PLAYLIST_003"));
		}
	}

	@Nested
	@DisplayName("콘텐츠 삭제")
	class RemoveContentFromPlaylist {

		@Test
		@DisplayName("콘텐츠 삭제 성공 시 204를 반환한다")
		void success() throws Exception {
			UUID contentId = UUID.randomUUID();
			doNothing().when(playlistService).removeContentFromPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(delete("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID contentId = UUID.randomUUID();
			UUID otherUserId = UUID.randomUUID();

			authenticateAs(otherUserId);

			doThrow(new PlaylistAccessDeniedException())
				.when(playlistService)
				.removeContentFromPlaylist(otherUserId, playlistId, contentId);

			mockMvc.perform(
					delete("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
				)
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("연결된 콘텐츠가 없으면 404를 반환한다")
		void notFound() throws Exception {
			UUID contentId = UUID.randomUUID();
			doThrow(new PlaylistContentNotFoundException())
					.when(playlistService).removeContentFromPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(delete("/api/playlists/{playlistId}/contents/{contentId}", playlistId, contentId))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PLAYLIST_004"));
		}
	}
}