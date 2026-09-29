package com.mopl.realtime.watchingsession.service;

import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class WatchingSubscriptionBroker {

	private final AbstractBrokerMessageHandler brokerMessageHandler;

	public WatchingSubscriptionBroker(
		@Qualifier("simpleBrokerMessageHandler")
		AbstractBrokerMessageHandler brokerMessageHandler
	) {
		this.brokerMessageHandler = brokerMessageHandler;
	}

	public void unsubscribeAll(
		String webSocketSessionId,
		Set<String> subscriptionIds
	) {
		for (String subscriptionId : subscriptionIds) {
			StompHeaderAccessor accessor =
				StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);

			accessor.setSessionId(webSocketSessionId);
			accessor.setSubscriptionId(subscriptionId);

			Message<byte[]> message =
				MessageBuilder.createMessage(
					new byte[0],
					accessor.getMessageHeaders()
				);

			brokerMessageHandler.handleMessage(message);
		}
	}
}