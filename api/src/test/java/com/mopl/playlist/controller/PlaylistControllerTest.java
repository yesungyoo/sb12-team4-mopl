package com.mopl.playlist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistAccessDeniedException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistListResponse;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.Disabled;
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

@WebMvcTest(PlaylistController.class)
class PlaylistControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private PlaylistService playlistService;

	private UUID playlistId;
	private UUID ownerId;
	private PlaylistResponse sampleResponse;

	{
		playlistId = UUID.randomUUID();
		ownerId = UUID.randomUUID();
		sampleResponse = new PlaylistResponse(
			playlistId, ownerId, "길동", "http://image.url",
			"제목", "설명", LocalDateTime.now(),
			0L, false, Collections.emptyList()
		);
	}

	@Nested
	@DisplayName("단건 조회")
	class GetPlaylist {

		@Test
		@DisplayName("존재하는 플레이리스트를 조회하면 200과 본문을 반환한다")
		void success() throws Exception {
			when(playlistService.getPlaylist(eq(playlistId), any())).thenReturn(sampleResponse);

			mockMvc.perform(get("/playlists/{playlistId}", playlistId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(playlistId.toString()))
				.andExpect(jsonPath("$.title").value("제목"));
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 조회하면 404와 오류 코드를 반환한다")
		void notFound() throws Exception {
			when(playlistService.getPlaylist(eq(playlistId), any())).thenThrow(new PlaylistNotFoundException());

			mockMvc.perform(get("/playlists/{playlistId}", playlistId))
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
			PlaylistListResponse response = new PlaylistListResponse(
				List.of(sampleResponse), null, null, false,
				"updatedAt", "DESCENDING", 1L
			);
			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), any(), any(), any()))
				.thenReturn(response);

			mockMvc.perform(get("/playlists")
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value(playlistId.toString()))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@Disabled("공통 핸들러의 필수 파라미터 누락 400 처리 후 활성화 (GlobalExceptionHandler 후속 이슈)")
		@DisplayName("limit 파라미터가 없으면 400을 반환한다")
		void missingLimit_badRequest() throws Exception {
			mockMvc.perform(get("/playlists")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("subscriberIdEqual 파라미터를 서비스에 전달한다")
		void success_withSubscriberIdEqual() throws Exception {
			UUID subscriberIdEqual = UUID.randomUUID();
			PlaylistListResponse response = new PlaylistListResponse(
				List.of(sampleResponse), null, null, false,
				"updatedAt", "DESCENDING", 1L
			);
			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), eq(subscriberIdEqual), any(), any()))
				.thenReturn(response);

			mockMvc.perform(get("/playlists")
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
			PlaylistListResponse response = new PlaylistListResponse(
				List.of(sampleResponse), null, null, false,
				"updatedAt", "DESCENDING", 1L
			);
			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), any(), eq(ownerIdEqual), any()))
				.thenReturn(response);

			mockMvc.perform(get("/playlists")
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING")
					.param("ownerIdEqual", ownerIdEqual.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("keywordLike 파라미터를 서비스에 전달한다")
		void success_withKeywordLike() throws Exception {
			String keywordLike = "비 오는 날";
			PlaylistListResponse response = new PlaylistListResponse(
				List.of(sampleResponse), null, null, false,
				"updatedAt", "DESCENDING", 1L
			);
			when(playlistService.getPlaylists(any(), any(), eq(20), eq("updatedAt"), eq("DESCENDING"), any(), any(), any(), eq(keywordLike)))
				.thenReturn(response);

			mockMvc.perform(get("/playlists")
					.param("limit", "20")
					.param("sortBy", "updatedAt")
					.param("sortDirection", "DESCENDING")
					.param("keywordLike", keywordLike))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1));
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

			mockMvc.perform(post("/playlists")
					.param("requesterId", ownerId.toString())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/playlists/" + playlistId));
		}

		@Test
		@DisplayName("빈 제목으로 생성 요청하면 400을 반환한다")
		void blankTitle_badRequest() throws Exception {
			PlaylistCreateRequest request = new PlaylistCreateRequest("", "설명");

			mockMvc.perform(post("/playlists")
					.param("requesterId", ownerId.toString())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
		}

		@Test
		@Disabled("공통 핸들러의 필수 파라미터 누락 400 처리 후 활성화 (GlobalExceptionHandler 후속 이슈)")
		@DisplayName("requesterId 없이 요청하면 400을 반환한다")
		void missingRequesterId_badRequest() throws Exception {
			PlaylistCreateRequest request = new PlaylistCreateRequest("제목", "설명");

			mockMvc.perform(post("/playlists")
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

			mockMvc.perform(patch("/playlists/{playlistId}", playlistId)
					.param("requesterId", ownerId.toString())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(playlistId.toString()));
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("새 제목", null);
			when(playlistService.updatePlaylist(any(), eq(playlistId), any(PlaylistUpdateRequest.class)))
				.thenThrow(new PlaylistAccessDeniedException());

			mockMvc.perform(patch("/playlists/{playlistId}", playlistId)
					.param("requesterId", UUID.randomUUID().toString())
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

			mockMvc.perform(patch("/playlists/{playlistId}", playlistId)
					.param("requesterId", ownerId.toString())
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

			mockMvc.perform(delete("/playlists/{playlistId}", playlistId)
					.param("requesterId", ownerId.toString()))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID otherUserId = UUID.randomUUID();
			doThrow(new PlaylistAccessDeniedException())
				.when(playlistService).deletePlaylist(otherUserId, playlistId);

			mockMvc.perform(delete("/playlists/{playlistId}", playlistId)
					.param("requesterId", otherUserId.toString()))
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 삭제하면 404를 반환한다")
		void notFound() throws Exception {
			doThrow(new PlaylistNotFoundException())
				.when(playlistService).deletePlaylist(ownerId, playlistId);

			mockMvc.perform(delete("/playlists/{playlistId}", playlistId)
					.param("requesterId", ownerId.toString()))
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

			mockMvc.perform(post("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", ownerId.toString()))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID contentId = UUID.randomUUID();
			UUID otherUserId = UUID.randomUUID();
			doThrow(new PlaylistAccessDeniedException())
				.when(playlistService).addContentToPlaylist(otherUserId, playlistId, contentId);

			mockMvc.perform(post("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", otherUserId.toString()))
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("존재하지 않는 콘텐츠면 404를 반환한다")
		void contentNotFound() throws Exception {
			UUID contentId = UUID.randomUUID();
			doThrow(new ContentNotFoundException())
				.when(playlistService).addContentToPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(post("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", ownerId.toString()))
				.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("이미 추가된 콘텐츠면 400을 반환한다")
		void alreadyExists() throws Exception {
			UUID contentId = UUID.randomUUID();
			doThrow(new PlaylistContentAlreadyExistsException())
				.when(playlistService).addContentToPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(post("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", ownerId.toString()))
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

			mockMvc.perform(delete("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", ownerId.toString()))
				.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("소유자가 아니면 403을 반환한다")
		void notOwner_forbidden() throws Exception {
			UUID contentId = UUID.randomUUID();
			UUID otherUserId = UUID.randomUUID();
			doThrow(new PlaylistAccessDeniedException())
				.when(playlistService).removeContentFromPlaylist(otherUserId, playlistId, contentId);

			mockMvc.perform(delete("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", otherUserId.toString()))
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("연결된 콘텐츠가 없으면 404를 반환한다")
		void notFound() throws Exception {
			UUID contentId = UUID.randomUUID();
			doThrow(new PlaylistContentNotFoundException())
				.when(playlistService).removeContentFromPlaylist(ownerId, playlistId, contentId);

			mockMvc.perform(delete("/playlists/{playlistId}/contents/{contentId}", playlistId, contentId)
					.param("requesterId", ownerId.toString()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PLAYLIST_004"));
		}
	}
}