package com.mopl.playlist.dto;

import com.mopl.core.domain.content.entity.Content;

import java.util.UUID;

public record ContentSummary(
	UUID id,
	String title,
	String thumbnailUrl
) {
	public static ContentSummary from(Content content) {
		return new ContentSummary(
			content.getId(),
			content.getTitle(),
			content.getThumbnailUrl()
		);
	}
}