package com.mopl.playlist.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistSubscriptionNotFoundException;
import com.mopl.core.common.event.PlaylistSubscribedEvent;
import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.playlist.entity.PlaylistSubscription;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaylistSubscriptionService {

	private final PlaylistRepository playlistRepository;
	private final PlaylistSubscriptionRepository playlistSubscriptionRepository;
	private final EntityManager entityManager;
	private final ApplicationEventPublisher eventPublisher;

	public PlaylistSubscriptionService(
		PlaylistRepository playlistRepository,
		PlaylistSubscriptionRepository playlistSubscriptionRepository,
		EntityManager entityManager,
		ApplicationEventPublisher eventPublisher
	) {
		this.playlistRepository = playlistRepository;
		this.playlistSubscriptionRepository = playlistSubscriptionRepository;
		this.entityManager = entityManager;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public void subscribe(UUID currentUserId, UUID playlistId) {
		Playlist playlist = playlistRepository.findById(playlistId)
			.orElseThrow(PlaylistNotFoundException::new);

		if (playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, currentUserId)) {
			throw new PlaylistSubscriptionAlreadyExistsException();
		}

		User subscriber = entityManager.find(User.class, currentUserId);
		if (subscriber == null) {
			// TODO: User 도메인 예외 체계(UserErrorCode.USER_NOT_FOUND 등) 생기면 교체 예정
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}

		try {
			playlistSubscriptionRepository.saveAndFlush(new PlaylistSubscription(playlist, subscriber));
		} catch (DataIntegrityViolationException e) {
			throw new PlaylistSubscriptionAlreadyExistsException();
		}

		eventPublisher.publishEvent(
			new PlaylistSubscribedEvent(
				playlist.getOwner().getId(),
				currentUserId,
				playlist.getTitle()
			)
		);
	}

	@Transactional
	public void unsubscribe(UUID currentUserId, UUID playlistId) {
		PlaylistSubscription subscription = playlistSubscriptionRepository
			.findByPlaylistIdAndSubscriberId(playlistId, currentUserId)
			.orElseThrow(PlaylistSubscriptionNotFoundException::new);

		playlistSubscriptionRepository.delete(subscription);
	}
}
