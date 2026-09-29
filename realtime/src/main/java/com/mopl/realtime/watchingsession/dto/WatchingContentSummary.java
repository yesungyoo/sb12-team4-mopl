package com.mopl.realtime.watchingsession.dto;

import com.mopl.core.common.enums.ContentType;

import java.util.List;
import java.util.UUID;

public record WatchingContentSummary(
	UUID id,
	ContentType type,
	String title,
	String description,
	String thumbnailUrl,
	List<String> tags,
	double averageRating,
	int reviewCount
) {
}