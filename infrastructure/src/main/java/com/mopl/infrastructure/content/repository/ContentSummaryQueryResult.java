package com.mopl.infrastructure.content.repository;

import java.util.List;

public record ContentSummaryQueryResult(
	List<String> tags,
	double averageRating,
	int reviewCount
) {
}