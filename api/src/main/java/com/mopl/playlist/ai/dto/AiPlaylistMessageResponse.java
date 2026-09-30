package com.mopl.playlist.ai.dto;

import com.mopl.core.common.enums.PlaylistAiMessageRole;

import java.time.LocalDateTime;
import java.util.UUID;

public record AiPlaylistMessageResponse(
	UUID id,
	PlaylistAiMessageRole role,
	String content,
	LocalDateTime createdAt
) {
}