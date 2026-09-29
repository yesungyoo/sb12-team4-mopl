package com.mopl.realtime.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.core.common.enums.UserRole;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import com.mopl.infrastructure.security.token.AccessTokenInvalidationService;
import com.mopl.realtime.directmessage.repository.ConversationRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private ConversationRepository conversationRepository;

	@Mock
	private MessageChannel messageChannel;

	@Mock
	private Claims claims;

	@Mock
	private AccessTokenInvalidationService accessTokenInvalidationService;

	private WebSocketAuthInterceptor interceptor;

	@BeforeEach
	void setUp() {
		interceptor = new WebSocketAuthInterceptor(
			accessTokenInvalidationService,
			jwtTokenProvider,
			conversationRepository
		);
	}

	@Nested
	@DisplayName("STOMP 연결 인증")
	class Connect {

		@Test
		@DisplayName("유효한 Access Token이면 Principal을 설정한다")
		void success() {
			UUID userId = UUID.randomUUID();
			Instant issuedAt = Instant.now();

			when(jwtTokenProvider.parseAccessTokenClaims("access-token"))
				.thenReturn(claims);
			when(jwtTokenProvider.getUserId(claims))
				.thenReturn(userId);
			when(jwtTokenProvider.getIssuedAt(claims))
				.thenReturn(issuedAt);
			when(accessTokenInvalidationService.isInvalidated(userId, issuedAt))
				.thenReturn(false);
			when(jwtTokenProvider.getEmail(claims))
				.thenReturn("user@test.com");
			when(jwtTokenProvider.getRole(claims))
				.thenReturn(UserRole.USER);

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.CONNECT);
			accessor.setNativeHeader(
				"Authorization",
				"Bearer access-token"
			);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			interceptor.preSend(message, messageChannel);

			assertThat(accessor.getUser())
				.isInstanceOf(StompPrincipal.class);

			StompPrincipal principal =
				(StompPrincipal)accessor.getUser();

			assertThat(principal.userId()).isEqualTo(userId);
			assertThat(principal.email()).isEqualTo("user@test.com");
			assertThat(principal.role()).isEqualTo(UserRole.USER);

			verify(accessTokenInvalidationService)
				.isInvalidated(userId, issuedAt);
		}

		@Test
		@DisplayName("Authorization 헤더가 없으면 연결을 거부한다")
		void authorizationHeaderMissing_throws() {
			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.CONNECT);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			assertThatThrownBy(() ->
				interceptor.preSend(message, messageChannel)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Access token is required");

			verifyNoInteractions(jwtTokenProvider, accessTokenInvalidationService);
		}

		@Test
		@DisplayName("무효화된 Access Token이면 연결을 거부한다")
		void invalidatedAccessToken_throws() {
			UUID userId = UUID.randomUUID();
			Instant issuedAt = Instant.now();

			when(jwtTokenProvider.parseAccessTokenClaims("access-token"))
				.thenReturn(claims);
			when(jwtTokenProvider.getUserId(claims))
				.thenReturn(userId);
			when(jwtTokenProvider.getIssuedAt(claims))
				.thenReturn(issuedAt);
			when(accessTokenInvalidationService.isInvalidated(userId, issuedAt))
				.thenReturn(true);

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.CONNECT);
			accessor.setNativeHeader(
				"Authorization",
				"Bearer access-token"
			);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			assertThatThrownBy(() ->
				interceptor.preSend(message, messageChannel)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Invalidated access token");

			assertThat(accessor.getUser()).isNull();
		}

		@Test
		@DisplayName("Refresh Token이면 연결을 거부한다")
		void refreshToken_throws() {
			when(jwtTokenProvider.parseAccessTokenClaims("refresh-token"))
				.thenThrow(new JwtException("Access token is required"));

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.CONNECT);
			accessor.setNativeHeader(
				"Authorization",
				"Bearer refresh-token"
			);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			assertThatThrownBy(() ->
				interceptor.preSend(message, messageChannel)
			)
				.isInstanceOf(JwtException.class)
				.hasMessage("Access token is required");

			assertThat(accessor.getUser()).isNull();
			verifyNoInteractions(accessTokenInvalidationService);
		}

		@Test
		@DisplayName("유효하지 않은 Access Token이면 연결을 거부한다")
		void invalidAccessToken_throws() {
			when(jwtTokenProvider.parseAccessTokenClaims("invalid-token"))
				.thenThrow(new JwtException("Invalid access token"));

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.CONNECT);

			accessor.setNativeHeader(
				"Authorization",
				"Bearer invalid-token"
			);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			assertThatThrownBy(() ->
				interceptor.preSend(message, messageChannel)
			)
				.isInstanceOf(JwtException.class)
				.hasMessage("Invalid access token");

			assertThat(accessor.getUser()).isNull();
		}
	}

	@Nested
	@DisplayName("DM 구독 권한")
	class Subscribe {

		@Test
		@DisplayName("대화 참여자는 DM 채널을 구독할 수 있다")
		void participant_success() {
			UUID userId = UUID.randomUUID();
			UUID conversationId = UUID.randomUUID();

			when(conversationRepository.existsByIdAndParticipantId(
				conversationId,
				userId
			)).thenReturn(true);

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
			accessor.setUser(
				new StompPrincipal(
					userId,
					"user@test.com",
					UserRole.USER
				)
			);
			accessor.setDestination(
				"/sub/conversations/"
					+ conversationId
					+ "/direct-messages"
			);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			interceptor.preSend(message, messageChannel);

			verify(conversationRepository)
				.existsByIdAndParticipantId(
					conversationId,
					userId
				);
		}

		@Test
		@DisplayName("대화 참여자가 아니면 DM 채널 구독을 거부한다")
		void notParticipant_throws() {
			UUID userId = UUID.randomUUID();
			UUID conversationId = UUID.randomUUID();

			when(conversationRepository.existsByIdAndParticipantId(
				conversationId,
				userId
			)).thenReturn(false);

			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
			accessor.setUser(
				new StompPrincipal(
					userId,
					"user@test.com",
					UserRole.USER
				)
			);
			accessor.setDestination(
				"/sub/conversations/"
					+ conversationId
					+ "/direct-messages"
			);
			accessor.setLeaveMutable(true);

			Message<byte[]> message = MessageBuilder.createMessage(
				new byte[0],
				accessor.getMessageHeaders()
			);

			assertThatThrownBy(() ->
				interceptor.preSend(message, messageChannel)
			)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Not a conversation participant");
		}
	}
}