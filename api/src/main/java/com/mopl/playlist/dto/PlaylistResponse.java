package com.mopl.playlist.dto;

import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.playlist.entity.Playlist;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlaylistResponse(
	UUID id,
	UserSummary owner,
	String title,
	String description,
	LocalDateTime updatedAt,
	long subscriberCount,
	boolean subscribedByMe,
	List<ContentSummary> contents
) {

	// 구독 정보가 무의미한 경우용 (0건/미구독 확정)
	public static PlaylistResponse from(Playlist playlist) {
		return from(
			playlist,
			Collections.emptyList(),
			Collections.emptyMap(),
			0L,
			false
		);
	}

	public static PlaylistResponse from(
		Playlist playlist,
		List<Content> contents,
		Map<UUID, ContentSearchDocument> documentsByContentId,
		long subscriberCount,
		boolean subscribedByMe
	) {
		List<ContentSummary> contentSummaries = contents.stream()
			.filter(content -> content.getDeletedAt() == null)
			.map(content -> ContentSummary.from(
				content,
				documentsByContentId.get(content.getId())
			))
			.toList();

		return new PlaylistResponse(
			playlist.getId(),
			UserSummary.from(playlist.getOwner()),
			playlist.getTitle(),
			playlist.getDescription(),
			playlist.getUpdatedAt(),
			subscriberCount,
			subscribedByMe,
			contentSummaries
		);
	}
}