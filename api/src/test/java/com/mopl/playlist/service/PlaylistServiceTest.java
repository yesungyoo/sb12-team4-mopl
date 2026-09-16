package com.mopl.playlist.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistAccessDeniedException;
import com.mopl.common.exception.playlist.PlaylistNotFoundException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistListResponse;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.mockito.ArgumentMatchers.eq;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.common.exception.playlist.PlaylistContentAlreadyExistsException;
import com.mopl.common.exception.playlist.PlaylistContentNotFoundException;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.playlist.entity.PlaylistContent;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;

import java.util.Map;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

	@Mock
	private PlaylistRepository playlistRepository;

	@Mock
	private EntityManager entityManager;

	@Mock
	private PlaylistContentRepository playlistContentRepository;

	@Mock
	private ContentRepository contentRepository;

	@Mock
	private PlaylistSubscriptionRepository playlistSubscriptionRepository;

	private PlaylistService playlistService;

	private UUID ownerId;
	private User owner;
	private UUID playlistId;
	private Playlist playlist;

	@BeforeEach
	void setUp() {
		playlistService = new PlaylistService(playlistRepository, entityManager, playlistContentRepository, contentRepository, playlistSubscriptionRepository);

		ownerId = UUID.randomUUID();
		owner = mock(User.class);
		lenient().when(owner.getId()).thenReturn(ownerId);
		lenient().when(owner.getName()).thenReturn("길동");
		lenient().when(owner.getProfileImageUrl()).thenReturn("http://image.url");

		playlistId = UUID.randomUUID();
		playlist = mock(Playlist.class);
		lenient().when(playlist.getId()).thenReturn(playlistId);
		lenient().when(playlist.getOwner()).thenReturn(owner);
		lenient().when(playlist.getTitle()).thenReturn("기존 제목");
		lenient().when(playlist.getDescription()).thenReturn("기존 설명");
		lenient().when(playlist.getUpdatedAt()).thenReturn(LocalDateTime.now());
	}

	@Nested
	@DisplayName("단건 조회")
	class GetPlaylist {

		@Test
		@DisplayName("존재하는 플레이리스트를 조회하면 응답으로 변환한다")
		void success() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findAllByPlaylistId(playlistId)).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistId(playlistId)).thenReturn(0L);

			PlaylistResponse response = playlistService.getPlaylist(playlistId, null);

			assertThat(response.id()).isEqualTo(playlistId);
			assertThat(response.ownerId()).isEqualTo(ownerId);
			assertThat(response.title()).isEqualTo("기존 제목");
			// 구독/콘텐츠 연결 기능이 아직 없어 스텁값이어야 한다
			assertThat(response.subscriberCount()).isZero();
			assertThat(response.subscribedByMe()).isFalse();
			assertThat(response.contents()).isEmpty();
		}

		@Test
		@DisplayName("연결된 콘텐츠가 있으면 응답의 contents에 포함한다")
		void success_withContents() {
			Content content = mock(Content.class);
			UUID contentId = UUID.randomUUID();
			when(content.getId()).thenReturn(contentId);
			when(content.getTitle()).thenReturn("콘텐츠 제목");
			when(content.getThumbnailUrl()).thenReturn("http://thumb.url");
			when(content.getDeletedAt()).thenReturn(null);

			PlaylistContent playlistContent = mock(PlaylistContent.class);
			when(playlistContent.getContent()).thenReturn(content);

			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findAllByPlaylistId(playlistId))
				.thenReturn(List.of(playlistContent));

			PlaylistResponse response = playlistService.getPlaylist(playlistId, null);

			assertThat(response.contents()).hasSize(1);
			assertThat(response.contents().get(0).id()).isEqualTo(contentId);
			assertThat(response.contents().get(0).title()).isEqualTo("콘텐츠 제목");
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 조회하면 예외가 발생한다")
		void notFound() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistService.getPlaylist(playlistId, null))
				.isInstanceOf(PlaylistNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("목록 조회")
	class GetPlaylists {

		@Test
		@DisplayName("limit이 0 이하면 예외가 발생한다")
		void limitZeroOrNegative_throws() {
			assertThatThrownBy(() ->
				playlistService.getPlaylists(null, null, 0, "updatedAt", "DESCENDING", null, null, null, null)
			).isInstanceOf(MoplException.class);

			assertThatThrownBy(() ->
				playlistService.getPlaylists(null, null, -1, "updatedAt", "DESCENDING", null, null, null, null)
			).isInstanceOf(MoplException.class);

			verifyNoInteractions(playlistRepository);
		}

		@Test
		@DisplayName("cursor와 idAfter 중 하나만 있으면 예외가 발생한다")
		void cursorIdAfterMismatch_throws() {
			assertThatThrownBy(() ->
				playlistService.getPlaylists("2026-01-01T00:00:00", null, 20, "updatedAt", "DESCENDING", null, null, null, null)
			).isInstanceOf(MoplException.class);

			assertThatThrownBy(() ->
				playlistService.getPlaylists(null, UUID.randomUUID(), 20, "updatedAt", "DESCENDING", null, null, null, null)
			).isInstanceOf(MoplException.class);
		}

		@Test
		@DisplayName("지원하지 않는 sortBy 값이면 예외가 발생한다")
		void invalidSortBy_throws() {
			assertThatThrownBy(() ->
				playlistService.getPlaylists(null, null, 20, "invalidSort", "DESCENDING", null, null, null, null)
			).isInstanceOf(MoplException.class);
		}

		@Test
		@DisplayName("다음 페이지가 있으면 hasNext=true와 nextCursor를 반환한다")
		void success_hasNextTrue() {
			List<Playlist> playlists = List.of(playlist, playlist, playlist);
			when(playlistRepository.findAllByCursor(any(), any(), anyInt(), any(), any(), any(), any(), any()))
				.thenReturn(playlists);
			when(playlistRepository.countAllMatching(any(), any(), any())).thenReturn(10L);
			when(playlistContentRepository.findAllByPlaylistIdIn(any())).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistIdIn(any())).thenReturn(Map.of());

			PlaylistListResponse response = playlistService.getPlaylists(
				null, null, 2, "updatedAt", "DESCENDING", null, null, null, null
			);

			assertThat(response.hasNext()).isTrue();
			assertThat(response.data()).hasSize(2);
			assertThat(response.nextIdAfter()).isEqualTo(playlistId);
			assertThat(response.totalCount()).isEqualTo(10L);
		}

		@Test
		@DisplayName("다음 페이지가 없으면 hasNext=false를 반환한다")
		void success_hasNextFalse() {
			List<Playlist> playlists = List.of(playlist);
			when(playlistRepository.findAllByCursor(any(), any(), anyInt(), any(), any(), any(), any(), any()))
				.thenReturn(playlists);
			when(playlistRepository.countAllMatching(any(), any(), any())).thenReturn(1L);
			when(playlistContentRepository.findAllByPlaylistIdIn(any())).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistIdIn(any())).thenReturn(Map.of());

			PlaylistListResponse response = playlistService.getPlaylists(
				null, null, 20, "updatedAt", "DESCENDING", null, null, null, null
			);

			assertThat(response.hasNext()).isFalse();
			assertThat(response.nextCursor()).isNull();
			assertThat(response.nextIdAfter()).isNull();
		}

		@Test
		@DisplayName("limit이 최대치를 넘으면 100으로 보정해서 조회한다")
		void limitExceedsMax_clampedTo100() {
			when(playlistRepository.findAllByCursor(any(), any(), eq(101), any(), any(), any(), any(), any()))
				.thenReturn(List.of());
			when(playlistRepository.countAllMatching(any(), any(), any())).thenReturn(0L);
			when(playlistContentRepository.findAllByPlaylistIdIn(any())).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistIdIn(any())).thenReturn(Map.of());

			playlistService.getPlaylists(null, null, 200, "updatedAt", "DESCENDING", null, null, null, null);

			verify(playlistRepository).findAllByCursor(any(), any(), eq(101), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("subscriberIdEqual이 주어지면 필터 조건과 함께 조회하고 필터링된 totalCount를 반환한다")
		void success_withSubscriberIdEqual() {
			UUID subscriberIdEqual = UUID.randomUUID();
			List<Playlist> playlists = List.of(playlist);
			when(playlistRepository.findAllByCursor(any(), any(), anyInt(), any(), any(), eq(subscriberIdEqual), any(), any()))
				.thenReturn(playlists);
			when(playlistRepository.countAllMatching(eq(subscriberIdEqual), any(), any())).thenReturn(1L);
			when(playlistContentRepository.findAllByPlaylistIdIn(any())).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistIdIn(any())).thenReturn(Map.of());

			PlaylistListResponse response = playlistService.getPlaylists(
				null, null, 20, "updatedAt", "DESCENDING", null, subscriberIdEqual, null, null
			);

			assertThat(response.totalCount()).isEqualTo(1L);
			verify(playlistRepository).findAllByCursor(any(), any(), anyInt(), any(), any(), eq(subscriberIdEqual), any(), any());
		}

		@Test
		@DisplayName("ownerIdEqual이 주어지면 필터 조건과 함께 조회한다")
		void success_withOwnerIdEqual() {
			UUID ownerIdEqual = UUID.randomUUID();
			List<Playlist> playlists = List.of(playlist);
			when(playlistRepository.findAllByCursor(any(), any(), anyInt(), any(), any(), any(), eq(ownerIdEqual), any()))
				.thenReturn(playlists);
			when(playlistRepository.countAllMatching(any(), eq(ownerIdEqual), any())).thenReturn(1L);
			when(playlistContentRepository.findAllByPlaylistIdIn(any())).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistIdIn(any())).thenReturn(Map.of());

			PlaylistListResponse response = playlistService.getPlaylists(
				null, null, 20, "updatedAt", "DESCENDING", null, null, ownerIdEqual, null
			);

			assertThat(response.totalCount()).isEqualTo(1L);
			verify(playlistRepository).findAllByCursor(any(), any(), anyInt(), any(), any(), any(), eq(ownerIdEqual), any());
		}

		@Test
		@DisplayName("keywordLike가 주어지면 필터 조건과 함께 조회한다")
		void success_withKeywordLike() {
			String keywordLike = "비 오는 날";
			List<Playlist> playlists = List.of(playlist);
			when(playlistRepository.findAllByCursor(any(), any(), anyInt(), any(), any(), any(), any(), eq(keywordLike)))
				.thenReturn(playlists);
			when(playlistRepository.countAllMatching(any(), any(), eq(keywordLike))).thenReturn(1L);
			when(playlistContentRepository.findAllByPlaylistIdIn(any())).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistIdIn(any())).thenReturn(Map.of());

			PlaylistListResponse response = playlistService.getPlaylists(
				null, null, 20, "updatedAt", "DESCENDING", null, null, null, keywordLike
			);

			assertThat(response.totalCount()).isEqualTo(1L);
			verify(playlistRepository).findAllByCursor(any(), any(), anyInt(), any(), any(), any(), any(), eq(keywordLike));
		}
	}

	@Nested
	@DisplayName("생성")
	class CreatePlaylist {

		@Test
		@DisplayName("존재하지 않는 요청자로 생성하면 예외가 발생한다")
		void ownerNotFound_throws() {
			UUID requesterId = UUID.randomUUID();
			PlaylistCreateRequest request = new PlaylistCreateRequest("제목", "설명");
			when(entityManager.find(User.class, requesterId)).thenReturn(null);

			assertThatThrownBy(() -> playlistService.createPlaylist(requesterId, request))
				.isInstanceOf(MoplException.class);

			verify(playlistRepository, never()).save(any());
		}

		@Test
		@DisplayName("정상 요청이면 요청자를 owner로 하는 플레이리스트를 저장한다")
		void success() {
			PlaylistCreateRequest request = new PlaylistCreateRequest("제목", "설명");
			when(entityManager.find(User.class, ownerId)).thenReturn(owner);
			when(playlistRepository.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));

			ArgumentCaptor<Playlist> captor = ArgumentCaptor.forClass(Playlist.class);

			PlaylistResponse response = playlistService.createPlaylist(ownerId, request);

			verify(playlistRepository).save(captor.capture());
			Playlist saved = captor.getValue();

			assertThat(saved.getOwner()).isEqualTo(owner);
			assertThat(saved.getTitle()).isEqualTo("제목");
			assertThat(saved.getDescription()).isEqualTo("설명");
			assertThat(response.ownerId()).isEqualTo(ownerId);
		}
	}

	@Nested
	@DisplayName("수정")
	class UpdatePlaylist {

		@Test
		@DisplayName("소유자가 아니면 예외가 발생한다")
		void notOwner_throws() {
			UUID otherUserId = UUID.randomUUID();
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("새 제목", null);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));

			assertThatThrownBy(() -> playlistService.updatePlaylist(otherUserId, playlistId, request))
				.isInstanceOf(PlaylistAccessDeniedException.class);

			verify(playlist, never()).update(any(), any());
		}

		@Test
		@DisplayName("제목이 공백이면 예외가 발생하고 조회하지 않는다")
		void blankTitle_throws() {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("   ", null);

			assertThatThrownBy(() -> playlistService.updatePlaylist(ownerId, playlistId, request))
				.isInstanceOf(MoplException.class);

			verify(playlistRepository, never()).findById(any());
		}

		@Test
		@DisplayName("설명이 빈 문자열이면 예외가 발생한다")
		void blankDescription_throws() {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest(null, "");

			assertThatThrownBy(() -> playlistService.updatePlaylist(ownerId, playlistId, request))
				.isInstanceOf(MoplException.class);
		}

		@Test
		@DisplayName("정상 요청이면 필드를 수정하고 flush 한다")
		void success() {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("새 제목", "새 설명");
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findAllByPlaylistId(playlistId)).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistId(playlistId)).thenReturn(0L);
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, ownerId)).thenReturn(false);

			playlistService.updatePlaylist(ownerId, playlistId, request);

			verify(playlist).update("새 제목", "새 설명");
			verify(entityManager).flush();
		}

		@Test
		@DisplayName("수정 응답에도 기존 연결된 콘텐츠가 포함된다")
		void success_includesExistingContents() {
			Content content = mock(Content.class);
			when(content.getId()).thenReturn(UUID.randomUUID());
			when(content.getTitle()).thenReturn("연결된 콘텐츠");
			when(content.getThumbnailUrl()).thenReturn("http://thumb.url");
			when(content.getDeletedAt()).thenReturn(null);

			PlaylistContent playlistContent = mock(PlaylistContent.class);
			when(playlistContent.getContent()).thenReturn(content);

			PlaylistUpdateRequest request = new PlaylistUpdateRequest("새 제목", null);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findAllByPlaylistId(playlistId))
				.thenReturn(List.of(playlistContent));
			when(playlistSubscriptionRepository.countByPlaylistId(playlistId)).thenReturn(0L);
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, ownerId)).thenReturn(false);

			PlaylistResponse response = playlistService.updatePlaylist(ownerId, playlistId, request);

			assertThat(response.contents()).hasSize(1);
			assertThat(response.contents().get(0).title()).isEqualTo("연결된 콘텐츠");
		}

		@Test
		@DisplayName("title과 description이 모두 null이면 기존 값 유지 의도로 update를 호출한다")
		void allFieldsNull_stillCallsUpdate() {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest(null, null);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findAllByPlaylistId(playlistId)).thenReturn(List.of());
			when(playlistSubscriptionRepository.countByPlaylistId(playlistId)).thenReturn(0L);
			when(playlistSubscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, ownerId)).thenReturn(false);

			playlistService.updatePlaylist(ownerId, playlistId, request);

			verify(playlist).update(null, null);
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 수정하면 예외가 발생한다")
		void notFound_throws() {
			PlaylistUpdateRequest request = new PlaylistUpdateRequest("새 제목", null);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistService.updatePlaylist(ownerId, playlistId, request))
				.isInstanceOf(PlaylistNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("삭제")
	class DeletePlaylist {

		@Test
		@DisplayName("소유자가 아니면 예외가 발생하고 삭제하지 않는다")
		void notOwner_throws() {
			UUID otherUserId = UUID.randomUUID();
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));

			assertThatThrownBy(() -> playlistService.deletePlaylist(otherUserId, playlistId))
				.isInstanceOf(PlaylistAccessDeniedException.class);

			verify(playlistRepository, never()).delete(any());
		}

		@Test
		@DisplayName("소유자가 맞으면 삭제한다")
		void success() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));

			playlistService.deletePlaylist(ownerId, playlistId);

			verify(playlistRepository).delete(playlist);
		}

		@Test
		@DisplayName("존재하지 않는 플레이리스트를 삭제하면 예외가 발생한다")
		void notFound_throws() {
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistService.deletePlaylist(ownerId, playlistId))
				.isInstanceOf(PlaylistNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("콘텐츠 추가")
	class AddContentToPlaylist {

		@Test
		@DisplayName("소유자가 아니면 예외가 발생한다")
		void notOwner_throws() {
			UUID otherUserId = UUID.randomUUID();
			UUID contentId = UUID.randomUUID();
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));

			assertThatThrownBy(() -> playlistService.addContentToPlaylist(otherUserId, playlistId, contentId))
				.isInstanceOf(PlaylistAccessDeniedException.class);

			verifyNoInteractions(contentRepository, playlistContentRepository);
		}

		@Test
		@DisplayName("존재하지 않는 콘텐츠면 예외가 발생한다")
		void contentNotFound_throws() {
			UUID contentId = UUID.randomUUID();
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistService.addContentToPlaylist(ownerId, playlistId, contentId))
				.isInstanceOf(ContentNotFoundException.class);
		}

		@Test
		@DisplayName("이미 추가된 콘텐츠면 예외가 발생한다")
		void alreadyExists_throws() {
			UUID contentId = UUID.randomUUID();
			Content content = mock(Content.class);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.of(content));
			when(playlistContentRepository.existsByPlaylistIdAndContentId(playlistId, contentId)).thenReturn(true);

			assertThatThrownBy(() -> playlistService.addContentToPlaylist(ownerId, playlistId, contentId))
				.isInstanceOf(PlaylistContentAlreadyExistsException.class);

			verify(playlistContentRepository, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("정상 요청이면 콘텐츠를 추가한다")
		void success() {
			UUID contentId = UUID.randomUUID();
			Content content = mock(Content.class);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.of(content));
			when(playlistContentRepository.existsByPlaylistIdAndContentId(playlistId, contentId)).thenReturn(false);

			playlistService.addContentToPlaylist(ownerId, playlistId, contentId);

			verify(playlistContentRepository).saveAndFlush(any(PlaylistContent.class));
		}

		@Test
		@DisplayName("동시 요청으로 저장 시점에 UNIQUE 제약 위반이 나면 이미 추가된 콘텐츠 예외로 변환한다")
		void concurrentInsert_convertsToAlreadyExistsException() {
			UUID contentId = UUID.randomUUID();
			Content content = mock(Content.class);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(contentRepository.findByIdAndDeletedAtIsNull(contentId)).thenReturn(Optional.of(content));
			when(playlistContentRepository.existsByPlaylistIdAndContentId(playlistId, contentId)).thenReturn(false);
			when(playlistContentRepository.saveAndFlush(any(PlaylistContent.class)))
				.thenThrow(new DataIntegrityViolationException("unique constraint violation"));

			assertThatThrownBy(() -> playlistService.addContentToPlaylist(ownerId, playlistId, contentId))
				.isInstanceOf(PlaylistContentAlreadyExistsException.class);
		}
	}

	@Nested
	@DisplayName("콘텐츠 삭제")
	class RemoveContentFromPlaylist {

		@Test
		@DisplayName("소유자가 아니면 예외가 발생한다")
		void notOwner_throws() {
			UUID otherUserId = UUID.randomUUID();
			UUID contentId = UUID.randomUUID();
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));

			assertThatThrownBy(() -> playlistService.removeContentFromPlaylist(otherUserId, playlistId, contentId))
				.isInstanceOf(PlaylistAccessDeniedException.class);

			verifyNoInteractions(playlistContentRepository);
		}

		@Test
		@DisplayName("연결된 콘텐츠가 없으면 예외가 발생한다")
		void notFound_throws() {
			UUID contentId = UUID.randomUUID();
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findByPlaylistIdAndContentId(playlistId, contentId))
				.thenReturn(Optional.empty());

			assertThatThrownBy(() -> playlistService.removeContentFromPlaylist(ownerId, playlistId, contentId))
				.isInstanceOf(PlaylistContentNotFoundException.class);
		}

		@Test
		@DisplayName("정상 요청이면 콘텐츠 연결을 삭제한다")
		void success() {
			UUID contentId = UUID.randomUUID();
			PlaylistContent playlistContent = mock(PlaylistContent.class);
			when(playlistRepository.findById(playlistId)).thenReturn(Optional.of(playlist));
			when(playlistContentRepository.findByPlaylistIdAndContentId(playlistId, contentId))
				.thenReturn(Optional.of(playlistContent));

			playlistService.removeContentFromPlaylist(ownerId, playlistId, contentId);

			verify(playlistContentRepository).delete(playlistContent);
		}
	}
}