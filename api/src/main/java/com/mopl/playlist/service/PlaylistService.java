package com.mopl.playlist.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.playlist.PlaylistAccessDeniedException;
import com.mopl.common.exception.playlist.PlaylistContentAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistContentNotFoundException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.playlist.entity.PlaylistContent;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.dto.*;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;

import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PlaylistService {

	private static final int MAX_LIMIT = 100;

	private final PlaylistRepository playlistRepository;
	private final EntityManager entityManager;
	private final PlaylistContentRepository playlistContentRepository;
	private final ContentRepository contentRepository;
	private final PlaylistSubscriptionRepository playlistSubscriptionRepository;

	private List<Content> getContentsOf(UUID playlistId) {
		return playlistContentRepository.findAllByPlaylistId(playlistId).stream()
			.map(PlaylistContent::getContent)
			.toList();
	}

	public PlaylistService(
		PlaylistRepository playlistRepository,
		EntityManager entityManager,
		PlaylistContentRepository playlistContentRepository,
		ContentRepository contentRepository,
		PlaylistSubscriptionRepository playlistSubscriptionRepository
	) {
		this.playlistRepository = playlistRepository;
		this.entityManager = entityManager;
		this.playlistContentRepository = playlistContentRepository;
		this.contentRepository = contentRepository;
		this.playlistSubscriptionRepository = playlistSubscriptionRepository;
	}

	public PlaylistResponse getPlaylist(UUID playlistId, UUID requesterId) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		List<Content> contents = getContentsOf(playlistId);
		long subscriberCount = playlistSubscriptionRepository.countByPlaylistId(playlistId);
		boolean subscribedByMe = requesterId != null
			&& playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, requesterId);

		return PlaylistResponse.from(playlist, contents, subscriberCount, subscribedByMe);
	}

	public PlaylistListResponse getPlaylists(
		String cursor, UUID idAfter, int limit, String sortByParam, String sortDirectionParam,
		UUID requesterId, UUID subscriberIdEqual
	) {
		if (limit <= 0) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		if ((cursor == null) != (idAfter == null)) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		int safeLimit = Math.min(limit, MAX_LIMIT);
		PlaylistSortBy sortBy = PlaylistSortBy.from(sortByParam);
		SortDirection sortDirection = SortDirection.from(sortDirectionParam);

		List<Playlist> playlists = playlistRepository.findAllByCursor(
			cursor, idAfter, safeLimit + 1, sortBy, sortDirection, subscriberIdEqual
		);

		boolean hasNext = playlists.size() > safeLimit;
		List<Playlist> pageContent = hasNext ? playlists.subList(0, safeLimit) : playlists;

		String nextCursor = null;
		UUID nextIdAfter = null;
		if (hasNext) {
			Playlist last = pageContent.get(pageContent.size() - 1);
			nextIdAfter = last.getId();
			nextCursor = sortBy == PlaylistSortBy.UPDATED_AT
				? last.getUpdatedAt().toString()
				: String.valueOf(playlistSubscriptionRepository.countByPlaylistId(last.getId()));
		}

		List<PlaylistResponse> data = mapToResponsesWithContents(pageContent, requesterId);
		long totalCount = playlistRepository.countAllMatching(subscriberIdEqual);

		return new PlaylistListResponse(
			data, nextCursor, nextIdAfter, hasNext, sortByParam, sortDirectionParam, totalCount
		);
	}

	@Transactional
	public PlaylistResponse createPlaylist(UUID requesterId, PlaylistCreateRequest request) {
		User owner = entityManager.find(User.class, requesterId);
		if (owner == null) {
			// TODO: User 도메인 예외 체계(UserErrorCode.USER_NOT_FOUND 등) 생기면 교체 예정
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		Playlist playlist = new Playlist(owner, request.title(), request.description());
		Playlist saved = playlistRepository.save(playlist);

		return PlaylistResponse.from(saved);
	}

	@Transactional
	public PlaylistResponse updatePlaylist(UUID requesterId, UUID playlistId, PlaylistUpdateRequest request) {
		if (request.title() != null && request.title().isBlank()) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		if (request.description() != null && request.description().isBlank()) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, requesterId);

		playlist.update(request.title(), request.description());
		entityManager.flush();

		long subscriberCount = playlistSubscriptionRepository.countByPlaylistId(playlistId);
		boolean subscribedByMe = playlistSubscriptionRepository
			.existsByPlaylistIdAndSubscriberId(playlistId, requesterId);

		return PlaylistResponse.from(playlist, getContentsOf(playlistId), subscriberCount, subscribedByMe);
	}

	@Transactional
	public void deletePlaylist(UUID requesterId, UUID playlistId) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, requesterId);
		playlistRepository.delete(playlist);
	}

	@Transactional
	public void addContentToPlaylist(UUID requesterId, UUID playlistId, UUID contentId) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, requesterId);

		Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
			.orElseThrow(ContentNotFoundException::new);

		if (playlistContentRepository.existsByPlaylistIdAndContentId(playlistId, contentId)) {
			throw new PlaylistContentAlreadyExistsException();
		}

		try {
			playlistContentRepository.saveAndFlush(new PlaylistContent(playlist, content));
		} catch (DataIntegrityViolationException e) {
			throw new PlaylistContentAlreadyExistsException();
		}
	}

	@Transactional
	public void removeContentFromPlaylist(UUID requesterId, UUID playlistId, UUID contentId) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, requesterId);

		PlaylistContent playlistContent = playlistContentRepository
			.findByPlaylistIdAndContentId(playlistId, contentId)
			.orElseThrow(PlaylistContentNotFoundException::new);

		playlistContentRepository.delete(playlistContent);
	}

	private List<PlaylistResponse> mapToResponsesWithContents(List<Playlist> playlists, UUID requesterId) {
		List<UUID> playlistIds = playlists.stream().map(Playlist::getId).toList();

		Map<UUID, List<Content>> contentsByPlaylistId = playlistContentRepository
			.findAllByPlaylistIdIn(playlistIds).stream()
			.collect(Collectors.groupingBy(
				pc -> pc.getPlaylist().getId(),
				Collectors.mapping(PlaylistContent::getContent, Collectors.toList())
			));

		Map<UUID, Long> subscriberCountByPlaylistId = playlistSubscriptionRepository.countByPlaylistIdIn(playlistIds);

		Set<UUID> subscribedPlaylistIds = requesterId != null
			? playlistSubscriptionRepository.findSubscribedPlaylistIds(playlistIds, requesterId)
			: Set.of();

		return playlists.stream()
			.map(playlist -> PlaylistResponse.from(
				playlist,
				contentsByPlaylistId.getOrDefault(playlist.getId(), List.of()),
				subscriberCountByPlaylistId.getOrDefault(playlist.getId(), 0L),
				subscribedPlaylistIds.contains(playlist.getId())
			))
			.toList();
	}

	private Playlist findPlaylistOrThrow(UUID playlistId) {
		return playlistRepository.findById(playlistId).orElseThrow(PlaylistNotFoundException::new);
	}

	private void validateOwner(Playlist playlist, UUID requesterId) {
		if (!playlist.getOwner().getId().equals(requesterId)) {
			throw new PlaylistAccessDeniedException();
		}
	}
}