package com.mopl.playlist.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionNotFoundException;
import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.playlist.entity.PlaylistSubscription;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaylistSubscriptionService {

	private final PlaylistRepository playlistRepository;
	private final PlaylistSubscriptionRepository playlistSubscriptionRepository;
	private final EntityManager entityManager;

	public PlaylistSubscriptionService(
		PlaylistRepository playlistRepository,
		PlaylistSubscriptionRepository playlistSubscriptionRepository,
		EntityManager entityManager
	) {
		this.playlistRepository = playlistRepository;
		this.playlistSubscriptionRepository = playlistSubscriptionRepository;
		this.entityManager = entityManager;
	}

	@Transactional
	public void subscribe(UUID requesterId, UUID playlistId) {
		Playlist playlist = playlistRepository.findById(playlistId)
			.orElseThrow(PlaylistNotFoundException::new);

		if (playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, requesterId)) {
			throw new PlaylistSubscriptionAlreadyExistsException();
		}

		User subscriber = entityManager.find(User.class, requesterId);
		if (subscriber == null) {
			// TODO: User 도메인 예외 체계(UserErrorCode.USER_NOT_FOUND 등) 생기면 교체 예정
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		try {
			playlistSubscriptionRepository.saveAndFlush(new PlaylistSubscription(playlist, subscriber));
		} catch (DataIntegrityViolationException e) {
			throw new PlaylistSubscriptionAlreadyExistsException();
		}
	}

	@Transactional
	public void unsubscribe(UUID requesterId, UUID playlistId) {
		PlaylistSubscription subscription = playlistSubscriptionRepository
			.findByPlaylistIdAndSubscriberId(playlistId, requesterId)
			.orElseThrow(PlaylistSubscriptionNotFoundException::new);

		playlistSubscriptionRepository.delete(subscription);
	}
}