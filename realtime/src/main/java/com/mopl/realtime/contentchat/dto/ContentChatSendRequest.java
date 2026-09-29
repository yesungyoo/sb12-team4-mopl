package com.mopl.realtime.contentchat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContentChatSendRequest(
	@NotBlank
	@Size(max = 1000)
	String content
) {
}