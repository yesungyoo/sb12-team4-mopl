package com.mopl.realtime.watchingsession.listener;

import com.mopl.core.domain.watchingsession.model.WatchingSessionState;
import com.mopl.realtime.watchingsession.dto.ChangeType;
import com.mopl.realtime.watchingsession.dto.WatchingSessionChange;
import com.mopl.realtime.watchingsession.dto.WatchingSessionDto;
import com.mopl.realtime.watchingsession.service.WatchingSessionJoinResult;
import com.mopl.realtime.watchingsession.service.WatchingSessionService;
import com.mopl.realtime.watchingsession.service.WatchingSubscriptionBroker;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WatchingSessionEventListener {

	private static final Pattern WATCH_DESTINATION_PATTERN =
		Pattern.compile("^/sub/contents/([0-9a-fA-F-]{36})/watch$");

	private final WatchingSessionService watchingSessionService;
	private final SimpMessagingTemplate messagingTemplate;
	private final WatchingSubscriptionBroker watchingSubscriptionBroker;

	public WatchingSessionEventListener(
		WatchingSessionService watchingSessionService,
		SimpMessagingTemplate messagingTemplate,
		WatchingSubscriptionBroker watchingSubscriptionBroker
	) {
		this.watchingSessionService = watchingSessionService;
		this.messagingTemplate = messagingTemplate;
		this.watchingSubscriptionBroker = watchingSubscriptionBroker;
	}

	@EventListener
	public void handleSubscribe(SessionSubscribeEvent event) {
		StompHeaderAccessor accessor =
			StompHeaderAccessor.wrap(event.getMessage());

		String destination = accessor.getDestination();
		String webSocketSessionId = accessor.getSessionId();
		Principal principal = accessor.getUser();
		String subscriptionId = accessor.getSubscriptionId();

		if (destination == null
			|| webSocketSessionId == null
			|| subscriptionId == null
			|| principal == null) {
			return;
		}

		Matcher matcher = WATCH_DESTINATION_PATTERN.matcher(destination);

		if (!matcher.matches()) {
			return;
		}

		UUID contentId = UUID.fromString(matcher.group(1));
		UUID userId = UUID.fromString(principal.getName());

		WatchingSessionJoinResult result = watchingSessionService.join(
			userId,
			contentId,
			webSocketSessionId,
			subscriptionId
		);

		result.leftSession()
			.filter(previousSession ->
				!previousSession.contentId().equals(contentId))
			.ifPresent(previousSession -> {
				watchingSubscriptionBroker.unsubscribeAll(
					previousSession.webSocketSessionId(),
					result.leftSubscriptionIds()
				);

				broadcastLeave(previousSession);
			});

		WatchingSessionState session = result.joinedSession();

		WatchingSessionDto watchingSession =
			watchingSessionService.toDto(session);

		long watcherCount = watchingSessionService.count(contentId);

		WatchingSessionChange change = new WatchingSessionChange(
			ChangeType.JOIN,
			watchingSession,
			watcherCount
		);

		messagingTemplate.convertAndSend(
			"/sub/contents/" + contentId + "/watch",
			change
		);
	}

	@EventListener
	public void handleUnsubscribe(SessionUnsubscribeEvent event) {
		StompHeaderAccessor accessor =
			StompHeaderAccessor.wrap(event.getMessage());

		String webSocketSessionId = accessor.getSessionId();
		String subscriptionId = accessor.getSubscriptionId();

		if (webSocketSessionId == null || subscriptionId == null) {
			return;
		}

		watchingSessionService.leaveSubscription(
			webSocketSessionId,
			subscriptionId
		).ifPresent(this::broadcastLeave);
	}

	@EventListener
	public void handleDisconnect(SessionDisconnectEvent event) {
		String webSocketSessionId = event.getSessionId();

		watchingSessionService.leave(webSocketSessionId)
			.ifPresent(this::broadcastLeave);
	}

	private void broadcastLeave(WatchingSessionState session) {
		WatchingSessionDto watchingSession =
			watchingSessionService.toDto(session);

		long watcherCount =
			watchingSessionService.count(session.contentId());

		WatchingSessionChange change = new WatchingSessionChange(
			ChangeType.LEAVE,
			watchingSession,
			watcherCount
		);

		messagingTemplate.convertAndSend(
			"/sub/contents/" + session.contentId() + "/watch",
			change
		);
	}
}