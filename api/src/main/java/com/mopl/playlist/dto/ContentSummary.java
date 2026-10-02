package com.mopl.playlist.dto;

import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.document.ContentTagSearchDocument;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;

import java.util.List;
import java.util.UUID;

public record ContentSummary(
	UUID id,
	ContentType type,
	String title,
	String description,
	String thumbnailUrl,
	List<String> tags,
	double averageRating,
	long reviewCount
) {

	public static ContentSummary from(
		Content content,
		ContentSearchDocument document
	) {
		List<String> tags = document == null || document.getTags() == null
			? List.of()
			: document.getTags().stream()
			.map(ContentTagSearchDocument::getValue)
			.distinct()
			.toList();

		return new ContentSummary(
			content.getId(),
			content.getType(),
			content.getTitle(),
			content.getDescription(),
			content.getThumbnailUrl(),
			tags,
			document == null || document.getAverageRating() == null
				? 0.0
				: document.getAverageRating(),
			document == null || document.getReviewCount() == null
				? 0L
				: document.getReviewCount()
		);
	}
}