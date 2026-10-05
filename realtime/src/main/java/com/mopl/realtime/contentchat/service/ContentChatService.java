package com.mopl.realtime.contentchat.service;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.message.entity.ContentChatMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.contentchat.dto.ContentChatResponse;
import com.mopl.realtime.contentchat.repository.ContentChatMessageRepository;
import com.mopl.realtime.contentchat.repository.ContentRepository;
import com.mopl.realtime.contentchat.repository.UserRepository;
import java.util.UUID;
import com.mopl.core.common.enums.MessageType;
import com.mopl.realtime.moderation.service.MessageModerationService;
import com.mopl.realtime.moderation.dto.RuleAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContentChatService {

	private final ContentRepository contentRepository;
	private final UserRepository userRepository;
	private final ContentChatMessageRepository contentChatMessageRepository;
	private final MessageModerationService moderation;

	@Transactional
	public ContentChatResponse send(
		UUID contentId,
		UUID senderId,
		String message
	) {
		moderation.assertCanSend(senderId);
		Content content = contentRepository
			.findByIdAndDeletedAtIsNull(contentId)
			.orElseThrow(() ->
				new IllegalArgumentException("Content not found")
			);

		User sender = userRepository
			.findByIdAndDeletedAtIsNull(senderId)
			.orElseThrow(() ->
				new IllegalArgumentException("User not found")
			);

		var decision = moderation.inspect(senderId, MessageType.CONTENT_CHAT, contentId, message);

		ContentChatMessage contentChatMessage =
			new ContentChatMessage(
				content,
				sender,
				decision.content()
			);

		ContentChatMessage saved =
			contentChatMessageRepository.save(contentChatMessage);

		if (decision.action() != RuleAction.MASK) {
			moderation.reviewAfterCommit(senderId, MessageType.CONTENT_CHAT, contentId, saved.getId(), message, decision.action());
		}

		return ContentChatResponse.from(saved);
	}
}
