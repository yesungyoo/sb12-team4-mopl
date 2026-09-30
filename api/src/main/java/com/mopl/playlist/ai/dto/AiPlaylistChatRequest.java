package com.mopl.playlist.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiPlaylistChatRequest(
	@NotBlank(message = "메시지는 필수입니다.")
	@Size(max = 1000, message = "메시지는 1000자 이하여야 합니다.")
	String message
) {
}