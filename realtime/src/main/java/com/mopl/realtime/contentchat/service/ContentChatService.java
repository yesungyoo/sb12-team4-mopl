package com.mopl.realtime.contentchat.service;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.message.entity.ContentChatMessage;
import com.mopl.core.domain.user.entity.User;
import com.mopl.realtime.contentchat.dto.ContentChatResponse;
import com.mopl.realtime.contentchat.repository.ContentChatMessageRepository;
import com.mopl.realtime.contentchat.repository.ContentRepository;
import com.mopl.realtime.contentchat.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContentChatService {

	private final ContentRepository contentRepository;
	private final UserRepository userRepository;
	private final ContentChatMessageRepository contentChatMessageRepository;

	@Transactional
	public ContentChatResponse send(
		UUID contentId,
		UUID senderId,
		String message
	) {
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

		ContentChatMessage contentChatMessage =
			new ContentChatMessage(
				content,
				sender,
				message
			);

		ContentChatMessage saved =
			contentChatMessageRepository.save(contentChatMessage);

		return ContentChatResponse.from(saved);
	}
}