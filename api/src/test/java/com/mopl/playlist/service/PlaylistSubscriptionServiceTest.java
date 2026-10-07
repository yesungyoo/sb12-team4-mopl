package com.mopl.playlist.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistSubscriptionServiceTest {

	@Mock
	private PlaylistRepository playlistRepository;

	@Mock
	private PlaylistSubscriptionRepository playlistSubscriptionRepository;

	@Mock
	private EntityManager entityManager;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private PlaylistSubscriptionService playlistSubscriptionService;

	private UUID playlistId;
	private Playlist playlist;
	private UUID playlistOwnerId;
	private User playlistOwner;
	private UUID subscriberId;
	private User subscriber;

	@BeforeEach
	void setUp() {
		playlistSubscriptionService = new PlaylistSubscriptionService(
			playlistRepository, playlistSubscriptionRepository, entityManager, eventPublisher
		);

		playlistId = UUID.randomUUID();
		playlist = mock(Playlist.class);
		playlistOwnerId = UUID.randomUUID();
		playlistOwner = mock(User.class);
		lenient().when(playlist.getOwner()).thenReturn(playlistOwner);
		lenient().when(playlistOwner.getId()).thenReturn(playlistOwnerId);
		lenient().when(playlist.getTitle()).thenReturn("플레이리스트 제목");

		subscriberId = UUID.randomUUID();
		subscriber = mock(User.class);
	}

	@Nested
	@DisplayName("구독")
	class Subscribe {

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 구독하면 예외가 발생한다")
		void playlistNotFound_throws() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistSubscriptionService.subscribe(subscriberId, playlistId))
				.isInstanceOf(PlaylistNotFoundException.class);

			verifyNoInteractions(playlistSubscriptionRepository);
		}

		@Test
		@DisplayName("이미 구독한 플레이리스트면 예외가 발생한다")
		void alreadySubscribed_throws() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, subscriberId))
				.thenReturn(true);

			assertThatThrownBy(() -> playlistSubscriptionService.subscribe(subscriberId, playlistId))
				.isInstanceOf(PlaylistSubscriptionAlreadyExistsException.class);

			verify(playlistSubscriptionRepository, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("존재하지 않는 구독자로 구독하면 예외가 발생한다")
		void subscriberNotFound_throws() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, subscriberId))
				.thenReturn(false);
			when(entityManager.find(User.class, subscriberId)).thenReturn(null);

			assertThatThrownBy(() -> playlistSubscriptionService.subscribe(subscriberId, playlistId))
				.isInstanceOf(MoplException.class);

			verify(playlistSubscriptionRepository, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("정상 요청이면 구독을 저장한다")
		void success() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, subscriberId))
				.thenReturn(false);
			when(entityManager.find(User.class, subscriberId)).thenReturn(subscriber);

			playlistSubscriptionService.subscribe(subscriberId, playlistId);

			verify(playlistSubscriptionRepository).saveAndFlush(any(PlaylistSubscription.class));
			verify(eventPublisher).publishEvent(
				new PlaylistSubscribedEvent(playlistOwnerId, subscriberId, "플레이리스트 제목")
			);
		}

		@Test
		@DisplayName("동시 요청으로 저장 시점에 UNIQUE 제약 위반이 나면 이미 구독한 예외로 변환한다")
		void concurrentInsert_convertsToAlreadyExistsException() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, subscriberId))
				.thenReturn(false);
			when(entityManager.find(User.class, subscriberId)).thenReturn(subscriber);
			when(playlistSubscriptionRepository.saveAndFlush(any(PlaylistSubscription.class)))
				.thenThrow(new DataIntegrityViolationException("unique constraint violation"));

			assertThatThrownBy(() -> playlistSubscriptionService.subscribe(subscriberId, playlistId))
				.isInstanceOf(PlaylistSubscriptionAlreadyExistsException.class);
		}
	}

	@Nested
	@DisplayName("구독 취소")
	class Unsubscribe {

		@Test
		@DisplayName("구독하지 않은 플레이리스트를 취소하면 예외가 발생한다")
		void notFound_throws() {
			when(playlistSubscriptionRepository.findByPlaylistIdAndSubscriberId(playlistId, subscriberId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistSubscriptionService.unsubscribe(subscriberId, playlistId))
				.isInstanceOf(PlaylistSubscriptionNotFoundException.class);

			verify(playlistSubscriptionRepository, never()).delete(any());
		}

		@Test
		@DisplayName("정상 요청이면 구독을 삭제한다")
		void success() {
			PlaylistSubscription subscription = mock(PlaylistSubscription.class);
			when(playlistSubscriptionRepository.findByPlaylistIdAndSubscriberId(playlistId, subscriberId))
				.thenReturn(Optional.of(subscription));

			playlistSubscriptionService.unsubscribe(subscriberId, playlistId);

			verify(playlistSubscriptionRepository).delete(subscription);
		}
	}
}
