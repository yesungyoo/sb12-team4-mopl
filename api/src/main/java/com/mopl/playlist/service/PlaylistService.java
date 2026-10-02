package com.mopl.playlist.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.playlist.PlaylistAccessDeniedException;
import com.mopl.common.exception.playlist.PlaylistContentAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistContentNotFoundException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.playlist.entity.PlaylistContent;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.dto.*;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.content.search.document.ContentSearchDocument;

import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class PlaylistService {

	private static final int MAX_LIMIT = 100;

	private final PlaylistRepository playlistRepository;
	private final EntityManager entityManager;
	private final PlaylistContentRepository playlistContentRepository;
	private final ContentRepository contentRepository;
	private final ContentSearchService contentSearchService;
	private final PlaylistSubscriptionRepository playlistSubscriptionRepository;

	private final Validator validator;

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
		ContentSearchService contentSearchService,
		PlaylistSubscriptionRepository playlistSubscriptionRepository,
		Validator validator
	) {
		this.playlistRepository = playlistRepository;
		this.entityManager = entityManager;
		this.playlistContentRepository = playlistContentRepository;
		this.contentRepository = contentRepository;
		this.contentSearchService = contentSearchService;
		this.playlistSubscriptionRepository = playlistSubscriptionRepository;
		this.validator = validator;
	}

	public PlaylistResponse getPlaylist(UUID playlistId, UUID currentUserId) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		List<Content> contents = getContentsOf(playlistId);
		Map<UUID, ContentSearchDocument> documentsByContentId =
			findDocumentsByContentIdsOrEmpty(
				contents.stream()
					.map(Content::getId)
					.toList()
			);
		long subscriberCount = playlistSubscriptionRepository.countByPlaylistId(playlistId);

		boolean subscribedByMe = currentUserId != null
			&& playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(
			playlistId,
			currentUserId
		);

		return PlaylistResponse.from(
			playlist,
			contents,
			documentsByContentId,
			subscriberCount,
			subscribedByMe
		);
	}

	public CursorResponse<PlaylistResponse> getPlaylists(
		String cursor,
		UUID idAfter,
		int limit,
		String sortByParam,
		String sortDirectionParam,
		UUID currentUserId,
		UUID subscriberIdEqual,
		UUID ownerIdEqual,
		String keywordLike
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
			cursor, idAfter, safeLimit + 1, sortBy, sortDirection, subscriberIdEqual, ownerIdEqual, keywordLike
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

		List<PlaylistResponse> data =
			mapToResponsesWithContents(pageContent, currentUserId);

		long totalCount =
			playlistRepository.countAllMatching(
				subscriberIdEqual,
				ownerIdEqual,
				keywordLike
			);

		return CursorResponse.of(
			data,
			nextCursor,
			nextIdAfter != null ? nextIdAfter.toString() : null,
			hasNext,
			totalCount,
			sortByParam,
			sortDirectionParam
		);
	}

	@Transactional
	public PlaylistResponse createPlaylist(
		UUID currentUserId,
		PlaylistCreateRequest request
	) {
		Set<ConstraintViolation<PlaylistCreateRequest>> violations =
			validator.validate(request);

		if (!violations.isEmpty()) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		User owner = entityManager.find(User.class, currentUserId);
		if (owner == null) {
			throw new MoplException(UserErrorCode.USER_NOT_FOUND);
		}

		Playlist playlist = new Playlist(owner, request.title(), request.description());
		Playlist saved = playlistRepository.save(playlist);

		return PlaylistResponse.from(saved);
	}

	@Transactional
	public PlaylistResponse createPlaylistWithContents(
		UUID currentUserId,
		PlaylistCreateRequest request,
		List<UUID> contentIds
	) {
		PlaylistResponse playlist = createPlaylist(
			currentUserId,
			request
		);

		for (UUID contentId : contentIds) {
			addContentToPlaylist(
				currentUserId,
				playlist.id(),
				contentId
			);
		}

		return getPlaylist(
			playlist.id(),
			currentUserId
		);
	}

	@Transactional
	public PlaylistResponse updatePlaylist(
		UUID currentUserId,
		UUID playlistId,
		PlaylistUpdateRequest request
	) {
		if (request.title() != null && request.title().isBlank()) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		if (request.description() != null && request.description().isBlank()) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, currentUserId);

		playlist.update(request.title(), request.description());
		entityManager.flush();

		long subscriberCount = playlistSubscriptionRepository.countByPlaylistId(playlistId);
		boolean subscribedByMe = playlistSubscriptionRepository
			.existsByPlaylistIdAndSubscriberId(
				playlistId,
				currentUserId
			);

		List<Content> contents = getContentsOf(playlistId);

		Map<UUID, ContentSearchDocument> documentsByContentId =
			findDocumentsByContentIdsOrEmpty(
				contents.stream()
					.map(Content::getId)
					.toList()
			);

		return PlaylistResponse.from(
			playlist,
			contents,
			documentsByContentId,
			subscriberCount,
			subscribedByMe
		);
	}

	@Transactional
	public void deletePlaylist(UUID currentUserId, UUID playlistId) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, currentUserId);
		playlistRepository.delete(playlist);
	}

	@Transactional
	public void addContentToPlaylist(
		UUID currentUserId,
		UUID playlistId,
		UUID contentId
	) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, currentUserId);

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
	public void removeContentFromPlaylist(
		UUID currentUserId,
		UUID playlistId,
		UUID contentId
	) {
		Playlist playlist = findPlaylistOrThrow(playlistId);
		validateOwner(playlist, currentUserId);

		PlaylistContent playlistContent = playlistContentRepository
			.findByPlaylistIdAndContentId(playlistId, contentId)
			.orElseThrow(PlaylistContentNotFoundException::new);

		playlistContentRepository.delete(playlistContent);
	}

	private List<PlaylistResponse> mapToResponsesWithContents(
		List<Playlist> playlists,
		UUID currentUserId
	) {
		List<UUID> playlistIds = playlists.stream().map(Playlist::getId).toList();

		Map<UUID, List<Content>> contentsByPlaylistId = playlistContentRepository
			.findAllByPlaylistIdIn(playlistIds).stream()
			.collect(Collectors.groupingBy(
				pc -> pc.getPlaylist().getId(),
				Collectors.mapping(PlaylistContent::getContent, Collectors.toList())
			));

		List<UUID> contentIds = contentsByPlaylistId.values().stream()
			.flatMap(List::stream)
			.map(Content::getId)
			.distinct()
			.toList();

		Map<UUID, ContentSearchDocument> documentsByContentId =
			findDocumentsByContentIdsOrEmpty(contentIds);

		Map<UUID, Long> subscriberCountByPlaylistId = playlistSubscriptionRepository.countByPlaylistIdIn(playlistIds);

		Set<UUID> subscribedPlaylistIds = currentUserId != null
			? playlistSubscriptionRepository.findSubscribedPlaylistIds(
			playlistIds,
			currentUserId
		)
			: Set.of();

		return playlists.stream()
			.map(playlist -> PlaylistResponse.from(
				playlist,
				contentsByPlaylistId.getOrDefault(playlist.getId(), List.of()),
				documentsByContentId,
				subscriberCountByPlaylistId.getOrDefault(playlist.getId(), 0L),
				subscribedPlaylistIds.contains(playlist.getId())
			))
			.toList();
	}

	private Map<UUID, ContentSearchDocument> findDocumentsByContentIdsOrEmpty(List<UUID> contentIds) {
		try {
			return contentSearchService.findDocumentsByContentIds(contentIds);
		} catch (DataAccessResourceFailureException | UncategorizedElasticsearchException e) {
			log.warn("플레이리스트 콘텐츠 검색 보강 실패. contentCount={}", contentIds.size(), e);
			return Map.of();
		}
	}

	private Playlist findPlaylistOrThrow(UUID playlistId) {
		return playlistRepository.findById(playlistId).orElseThrow(PlaylistNotFoundException::new);
	}

	private void validateOwner(
		Playlist playlist,
		UUID currentUserId
	) {
		if (!playlist.getOwner().getId().equals(currentUserId)) {
			throw new PlaylistAccessDeniedException();
		}
	}
}