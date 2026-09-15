package com.mopl.playlist.dto;

import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.playlist.entity.Playlist;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record PlaylistResponse(
	UUID id,
	UUID ownerId,
	String ownerName,
	String ownerProfileImageUrl,
	String title,
	String description,
	LocalDateTime updatedAt,
	long subscriberCount,
	boolean subscribedByMe,
	List<ContentSummary> contents
) {
	// 구독 정보가 무의미한 경우용 (0건/미구독 확정)
	public static PlaylistResponse from(Playlist playlist) {
		return from(playlist, Collections.emptyList(), 0L, false);
	}

	public static PlaylistResponse from(Playlist playlist, List<Content> contents, long subscriberCount, boolean subscribedByMe) {
		List<ContentSummary> contentSummaries = contents.stream()
			.filter(content -> content.getDeletedAt() == null) // soft delete된 콘텐츠 제외
			.map(ContentSummary::from)
			.toList();

		return new PlaylistResponse(
			playlist.getId(),
			playlist.getOwner().getId(),
			playlist.getOwner().getName(),
			playlist.getOwner().getProfileImageUrl(),
			playlist.getTitle(),
			playlist.getDescription(),
			playlist.getUpdatedAt(),
			subscriberCount,
			subscribedByMe,
			contentSummaries
		);
	}
}