package com.mopl.playlist.ai.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AiPlaylistSessionResponse(
	UUID id,
	String title,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}