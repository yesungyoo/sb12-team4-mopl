package com.mopl.realtime.directmessage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DirectMessageSendRequest(

	@NotBlank
	@Size(max = 1000)
	String content
) {
}