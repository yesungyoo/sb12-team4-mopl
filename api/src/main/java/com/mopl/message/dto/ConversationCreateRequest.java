package com.mopl.message.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConversationCreateRequest(
	@NotNull(message = "대화 상대방 ID는 필수입니다.")
	UUID withUserId
) {
}