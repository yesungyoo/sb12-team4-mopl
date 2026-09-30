package com.mopl.playlist.ai.dto;

import java.util.UUID;

public record AiPlaylistChatResponse(
	UUID sessionId,
	String message
) {
}