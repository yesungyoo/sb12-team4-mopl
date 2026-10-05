package com.mopl.realtime.global.security;

import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import com.mopl.realtime.directmessage.repository.ConversationRepository;
import io.jsonwebtoken.Claims;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import com.mopl.infrastructure.security.token.AccessTokenInvalidationService;

@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

	private static final String AUTHORIZATION = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private static final String CONVERSATION_SUBSCRIBE_PREFIX = "/sub/conversations/";
	private static final String DIRECT_MESSAGE_SUFFIX = "/direct-messages";
	private static final String UUID_PATH = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	private static final Pattern SEND_DESTINATION = Pattern.compile(
		"/pub/(contents/" + UUID_PATH + "/chat|conversations/" + UUID_PATH + "/direct-messages)");
	private static final Pattern CONTENT_SUBSCRIPTION = Pattern.compile(
		"/sub/contents/" + UUID_PATH + "/(chat|watch)");
	private static final Pattern DM_SUBSCRIPTION = Pattern.compile(
		CONVERSATION_SUBSCRIBE_PREFIX + UUID_PATH + DIRECT_MESSAGE_SUFFIX);

	private final AccessTokenInvalidationService accessTokenInvalidationService;
	private final JwtTokenProvider jwtTokenProvider;
	private final ConversationRepository conversationRepository;

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor =
			MessageHeaderAccessor.getAccessor(
				message,
				StompHeaderAccessor.class
			);

		if (accessor == null) {
			return message;
		}

		StompCommand command = accessor.getCommand();

		if (StompCommand.CONNECT.equals(command)) {
			authenticate(accessor);
		}

		if (StompCommand.SEND.equals(command)) {
				requirePrincipal(accessor);
				String destination = accessor.getDestination();
				if (destination == null || !SEND_DESTINATION.matcher(destination).matches()) {
					throw new IllegalArgumentException("Invalid send destination");
				}
			}

			if (StompCommand.SUBSCRIBE.equals(command)) {
			authorizeSubscribe(accessor);
		}

		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
		String authorization = accessor.getFirstNativeHeader(AUTHORIZATION);

		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			throw new IllegalArgumentException("Access token is required");
		}

		String token = authorization.substring(BEARER_PREFIX.length()).trim();
		Claims claims = jwtTokenProvider.parseAccessTokenClaims(token);

		UUID userId = jwtTokenProvider.getUserId(claims);

		if (accessTokenInvalidationService.isInvalidated(
			userId,
			jwtTokenProvider.getIssuedAt(claims)
		)) {
			throw new IllegalArgumentException("Invalidated access token");
		}

		StompPrincipal principal = new StompPrincipal(
			userId,
			jwtTokenProvider.getEmail(claims),
			jwtTokenProvider.getRole(claims)
		);

		accessor.setUser(principal);
	}

	private void authorizeSubscribe(StompHeaderAccessor accessor) {
		StompPrincipal principal = requirePrincipal(accessor);
		String destination = accessor.getDestination();
		// 자기 오류 목적지를 제외한 사용자 경로와 와일드카드 구독은 허용하지 않는다.
		if ("/user/sub/errors".equals(destination)
			|| destination != null && CONTENT_SUBSCRIPTION.matcher(destination).matches()) {
			return;
		}
		if (destination == null || !DM_SUBSCRIPTION.matcher(destination).matches()) {
			throw new IllegalArgumentException("Invalid subscription destination");
		}
		UUID conversationId = extractConversationId(destination);
		if (!conversationRepository.existsByIdAndParticipantId(conversationId, principal.userId())) {
			throw new IllegalArgumentException("Not a conversation participant");
		}
	}

	private StompPrincipal requirePrincipal(StompHeaderAccessor accessor) {
		if (!(accessor.getUser() instanceof StompPrincipal principal)) {
			throw new IllegalArgumentException("Authentication is required");
		}
		return principal;
	}

	private UUID extractConversationId(String destination) {
		if (!destination.endsWith(DIRECT_MESSAGE_SUFFIX)) {
			throw new IllegalArgumentException("Invalid subscription destination");
		}

		int start = CONVERSATION_SUBSCRIBE_PREFIX.length();
		int end = destination.length() - DIRECT_MESSAGE_SUFFIX.length();

		if (start >= end) {
			throw new IllegalArgumentException("Invalid conversation id");
		}

		String conversationId = destination.substring(start, end);

		return UUID.fromString(conversationId);
	}
}
