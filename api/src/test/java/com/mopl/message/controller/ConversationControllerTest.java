package com.mopl.message.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.util.SecurityUtil;
import com.mopl.common.exception.message.ConversationAccessDeniedException;
import com.mopl.common.exception.message.ConversationNotFoundException;
import com.mopl.common.exception.message.ConversationSelfNotAllowedException;
import com.mopl.common.exception.message.DirectMessageNotFoundException;
import com.mopl.common.exception.message.DirectMessageReadNotAllowedException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.user.entity.User;
import com.mopl.message.dto.*;
import com.mopl.message.service.ConversationService;
import com.mopl.message.service.DirectMessageService;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Security(JWT) 도입으로 인한 필터체인 영향을 받지 않도록 처리.
// 이 테스트는 인증/인가 자체가 아니라 Conversation 컨트롤러 로직을 검증하는 것이 목적이므로 필터를 끈다.
// 요청자 식별은 SecurityUtil.getCurrentUserId()를 통해 이루어지므로, 정적 메서드를 목킹해서 검증한다.
@WebMvcTest(ConversationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ConversationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ConversationService conversationService;

	@MockitoBean
	private DirectMessageService directMessageService;

	@MockitoBean
	private UserRepository userRepository;

	private UUID requesterId;
	private UUID targetUserId;
	private UUID conversationId;
	private UUID directMessageId;
	private ConversationResponse sampleConversationResponse;

	private MockedStatic<SecurityUtil> securityUtilMock;

	{
		requesterId = UUID.randomUUID();
		targetUserId = UUID.randomUUID();
		conversationId = UUID.randomUUID();
		directMessageId = UUID.randomUUID();

		UserSummary partner = new UserSummary(targetUserId, "상대방", "http://image.url");
		sampleConversationResponse = new ConversationResponse(
			conversationId, partner, null, false
		);
	}

	@BeforeEach
	void setUpSecurityUtil() {
		securityUtilMock = mockStatic(SecurityUtil.class);
		securityUtilMock.when(SecurityUtil::getCurrentUserId).thenReturn(requesterId);
	}

	@AfterEach
	void tearDownSecurityUtil() {
		securityUtilMock.close();
	}

	@Nested
	@DisplayName("대화 생성")
	class CreateConversation {

		@Test
		@DisplayName("생성 성공 시 200과 본문을 반환한다")
		void success() throws Exception {
			User requester = mock(User.class);
			User target = mock(User.class);
			lenient().when(requester.getId()).thenReturn(requesterId);
			lenient().when(requester.getName()).thenReturn("나");
			lenient().when(target.getId()).thenReturn(targetUserId);
			lenient().when(target.getName()).thenReturn("상대방");

			when(userRepository.findById(requesterId)).thenReturn(Optional.of(requester));
			when(userRepository.findById(targetUserId)).thenReturn(Optional.of(target));

			Conversation conversation = mock(Conversation.class);
			lenient().when(conversation.getId()).thenReturn(conversationId);
			lenient().when(conversation.getUser1()).thenReturn(requester);
			lenient().when(conversation.getUser2()).thenReturn(target);

			when(conversationService.getOrCreateConversation(any(User.class), any(User.class)))
				.thenReturn(conversation);

			ConversationCreateRequest request = new ConversationCreateRequest(targetUserId);

			mockMvc.perform(post("/conversations")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(conversationId.toString()))
				.andExpect(jsonPath("$.with.userId").value(targetUserId.toString()));
		}

		@Test
		@DisplayName("요청자가 존재하지 않으면 404를 반환한다")
		void requesterNotFound() throws Exception {
			when(userRepository.findById(requesterId)).thenReturn(Optional.empty());

			ConversationCreateRequest request = new ConversationCreateRequest(targetUserId);

			mockMvc.perform(post("/conversations")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(UserErrorCode.USER_NOT_FOUND.getCode()));
		}

		@Test
		@DisplayName("대상 사용자가 존재하지 않으면 404를 반환한다")
		void targetNotFound() throws Exception {
			User requester = mock(User.class);
			lenient().when(requester.getId()).thenReturn(requesterId);

			when(userRepository.findById(requesterId)).thenReturn(Optional.of(requester));
			when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

			ConversationCreateRequest request = new ConversationCreateRequest(targetUserId);

			mockMvc.perform(post("/conversations")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(UserErrorCode.USER_NOT_FOUND.getCode()));
		}

		@Test
		@DisplayName("자기 자신과 대화를 생성하려 하면 400을 반환한다")
		void selfNotAllowed() throws Exception {
			User requester = mock(User.class);
			lenient().when(requester.getId()).thenReturn(requesterId);

			when(userRepository.findById(requesterId)).thenReturn(Optional.of(requester));
			when(conversationService.getOrCreateConversation(any(User.class), any(User.class)))
				.thenThrow(new ConversationSelfNotAllowedException());

			ConversationCreateRequest request = new ConversationCreateRequest(requesterId);

			mockMvc.perform(post("/conversations")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
		}
	}

	@Nested
	@DisplayName("대화 단건 조회")
	class GetConversation {

		@Test
		@DisplayName("존재하는 대화를 조회하면 200과 본문을 반환한다")
		void success() throws Exception {
			when(conversationService.getConversation(eq(conversationId), eq(requesterId)))
				.thenReturn(sampleConversationResponse);

			mockMvc.perform(get("/conversations/{conversationId}", conversationId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(conversationId.toString()));
		}

		@Test
		@DisplayName("존재하지 않는 대화를 조회하면 404를 반환한다")
		void notFound() throws Exception {
			when(conversationService.getConversation(eq(conversationId), eq(requesterId)))
				.thenThrow(new ConversationNotFoundException());

			mockMvc.perform(get("/conversations/{conversationId}", conversationId))
				.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("당사자가 아니면 403을 반환한다")
		void accessDenied() throws Exception {
			when(conversationService.getConversation(eq(conversationId), eq(requesterId)))
				.thenThrow(new ConversationAccessDeniedException());

			mockMvc.perform(get("/conversations/{conversationId}", conversationId))
				.andExpect(status().isForbidden());
		}
	}

	@Nested
	@DisplayName("특정 사용자와의 대화 조회")
	class GetConversationWith {

		@Test
		@DisplayName("조회 성공 시 200과 본문을 반환한다")
		void success() throws Exception {
			when(conversationService.getConversationWith(eq(requesterId), eq(targetUserId)))
				.thenReturn(sampleConversationResponse);

			mockMvc.perform(get("/conversations/with")
					.param("userId", targetUserId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(conversationId.toString()));
		}

		@Test
		@DisplayName("존재하지 않는 대화면 404를 반환한다")
		void notFound() throws Exception {
			when(conversationService.getConversationWith(eq(requesterId), eq(targetUserId)))
				.thenThrow(new ConversationNotFoundException());

			mockMvc.perform(get("/conversations/with")
					.param("userId", targetUserId.toString()))
				.andExpect(status().isNotFound());
		}
	}

	@Nested
	@DisplayName("대화 목록 조회")
	class GetConversations {

		@Test
		@DisplayName("목록 조회 성공 시 데이터와 페이지 정보를 반환한다")
		void success() throws Exception {
			CursorResponse<ConversationResponse> response = new CursorResponse<>(
				List.of(sampleConversationResponse), null, null, false,
				1L, "CREATED_AT", "DESCENDING"
			);

			when(conversationService.getConversations(
				eq(requesterId), any(), any(), any(), eq(20),
				any(SortDirection.class), any(ConversationSortBy.class)
			)).thenReturn(response);

			mockMvc.perform(get("/conversations")
					.param("limit", "20")
					.param("sortBy", "createdAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value(conversationId.toString()))
				.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("keywordLike 파라미터를 서비스에 전달한다")
		void success_withKeywordLike() throws Exception {
			String keywordLike = "상대방";
			CursorResponse<ConversationResponse> response = new CursorResponse<>(
				List.of(sampleConversationResponse), null, null, false,
				1L, "CREATED_AT", "DESCENDING"
			);

			when(conversationService.getConversations(
				eq(requesterId), eq(keywordLike), any(), any(), eq(20),
				any(SortDirection.class), any(ConversationSortBy.class)
			)).thenReturn(response);

			mockMvc.perform(get("/conversations")
					.param("keywordLike", keywordLike)
					.param("limit", "20")
					.param("sortBy", "createdAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1));
		}
	}

	@Nested
	@DisplayName("DM 목록 조회")
	class GetDirectMessages {

		@Test
		@DisplayName("목록 조회 성공 시 데이터와 페이지 정보를 반환한다")
		void success() throws Exception {
			UserSummary sender = new UserSummary(requesterId, "나", null);
			UserSummary receiver = new UserSummary(targetUserId, "상대방", null);
			DirectMessageResponse dmResponse = new DirectMessageResponse(
				directMessageId, conversationId, LocalDateTime.now(), sender, receiver, "안녕하세요"
			);
			CursorResponse<DirectMessageResponse> response = new CursorResponse<>(
				List.of(dmResponse), null, null, false,
				1L, "CREATED_AT", "DESCENDING"
			);

			when(directMessageService.getDirectMessages(
				eq(conversationId), eq(requesterId), any(), any(), eq(20),
				any(SortDirection.class), any(DirectMessageSortBy.class)
			)).thenReturn(response);

			mockMvc.perform(get("/conversations/{conversationId}/direct-messages", conversationId)
					.param("limit", "20")
					.param("sortBy", "createdAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value(directMessageId.toString()))
				.andExpect(jsonPath("$.totalCount").value(1));
		}

		@Test
		@DisplayName("당사자가 아니면 403을 반환한다")
		void accessDenied() throws Exception {
			when(directMessageService.getDirectMessages(
				eq(conversationId), eq(requesterId), any(), any(), eq(20),
				any(SortDirection.class), any(DirectMessageSortBy.class)
			)).thenThrow(new ConversationAccessDeniedException());

			mockMvc.perform(get("/conversations/{conversationId}/direct-messages", conversationId)
					.param("limit", "20")
					.param("sortBy", "createdAt")
					.param("sortDirection", "DESCENDING"))
				.andExpect(status().isForbidden());
		}
	}

	@Nested
	@DisplayName("DM 읽음 처리")
	class MarkAsRead {

		@Test
		@DisplayName("읽음 처리 성공 시 200을 반환한다")
		void success() throws Exception {
			doNothing().when(directMessageService).markAsRead(conversationId, directMessageId, requesterId);

			mockMvc.perform(post("/conversations/{conversationId}/direct-messages/{directMessageId}/read",
					conversationId, directMessageId))
				.andExpect(status().isOk());
		}

		@Test
		@DisplayName("수신자 본인이 아니면 403을 반환한다")
		void notAllowed() throws Exception {
			doThrow(new DirectMessageReadNotAllowedException())
				.when(directMessageService).markAsRead(conversationId, directMessageId, requesterId);

			mockMvc.perform(post("/conversations/{conversationId}/direct-messages/{directMessageId}/read",
					conversationId, directMessageId))
				.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("존재하지 않는 DM이면 404를 반환한다")
		void notFound() throws Exception {
			doThrow(new DirectMessageNotFoundException())
				.when(directMessageService).markAsRead(conversationId, directMessageId, requesterId);

			mockMvc.perform(post("/conversations/{conversationId}/direct-messages/{directMessageId}/read",
					conversationId, directMessageId))
				.andExpect(status().isNotFound());
		}
	}
}