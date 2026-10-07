package com.mopl.realtime.directmessage.service;

import com.mopl.core.common.event.DirectMessageReceivedEvent;
import com.mopl.core.domain.message.entity.Conversation;
import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.directmessage.dto.DirectMessageResponse;
import com.mopl.realtime.directmessage.repository.ConversationRepository;
import com.mopl.realtime.directmessage.repository.DirectMessageRepository;
import java.util.UUID;
import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.service.MessageModerationService;
import com.mopl.realtime.moderation.dto.RuleAction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DirectMessageService {

	private final ConversationRepository conversationRepository;
	private final DirectMessageRepository directMessageRepository;
	private final MessageModerationService moderation;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public DirectMessageResponse send(
		UUID conversationId,
		UUID senderId,
		String content
	) {
		moderation.assertCanSend(senderId);
		Conversation conversation = conversationRepository
			.findWithParticipantsById(conversationId)
			.orElseThrow(() ->
				new IllegalArgumentException("Conversation not found")
			);

		User sender;
		User receiver;

		if (conversation.getUser1().getId().equals(senderId)) {
			sender = conversation.getUser1();
			receiver = conversation.getUser2();
		} else if (conversation.getUser2().getId().equals(senderId)) {
			sender = conversation.getUser2();
			receiver = conversation.getUser1();
		} else {
			throw new IllegalArgumentException(
				"Not a conversation participant"
			);
		}

		var decision = moderation.inspect(senderId, MessageType.DM, conversationId, content);

		DirectMessage directMessage = new DirectMessage(
			conversation,
			sender,
			receiver,
			decision.content()
		);

		DirectMessage saved = directMessageRepository.save(directMessage);

		if (decision.action() != RuleAction.MASK) {
			moderation.reviewAfterCommit(senderId, MessageType.DM, conversationId, saved.getId(), content, decision.action());
		}

		eventPublisher.publishEvent(
			new DirectMessageReceivedEvent(receiver.getId(), sender.getId())
		);

		return DirectMessageResponse.from(saved);
	}
}
