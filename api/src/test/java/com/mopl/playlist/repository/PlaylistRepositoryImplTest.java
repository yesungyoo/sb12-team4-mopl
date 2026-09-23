package com.mopl.playlist.repository;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.dto.PlaylistSortBy;
import com.mopl.playlist.dto.SortDirection;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
	replace = AutoConfigureTestDatabase.Replace.ANY
)
@EnableJpaRepositories(basePackageClasses = PlaylistRepository.class)
@Import(PlaylistRepositoryImplTest.QuerydslTestConfig.class)
class PlaylistRepositoryImplTest {

	@TestConfiguration
	static class QuerydslTestConfig {

		@PersistenceContext
		private EntityManager entityManager;

		@Bean
		JPAQueryFactory jpaQueryFactory() {
			return new JPAQueryFactory(entityManager);
		}
	}

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PlaylistRepository playlistRepository;

	@Test
	@DisplayName("keywordLike는 제목 또는 설명에 포함된 플레이리스트를 조회한다")
	void findAllByCursor_keywordLikeMatchesTitleOrDescription() {
		User owner = persistUser(
			"playlist-owner@mopl.io",
			"플레이리스트 작성자"
		);

		Playlist titleMatched = persistPlaylist(
			owner,
			"비 오는 날 영화",
			"잔잔한 작품 모음"
		);

		Playlist descriptionMatched = persistPlaylist(
			owner,
			"주말 영화",
			"비 오는 날 보기 좋은 작품"
		);

		persistPlaylist(
			owner,
			"액션 영화",
			"빠른 전개의 작품 모음"
		);

		em.flush();
		em.clear();

		List<Playlist> result = playlistRepository.findAllByCursor(
			null,
			null,
			20,
			PlaylistSortBy.SUBSCRIBE_COUNT,
			SortDirection.DESCENDING,
			null,
			null,
			"비 오는 날"
		);

		assertThat(result)
			.extracting(Playlist::getId)
			.containsExactlyInAnyOrder(
				titleMatched.getId(),
				descriptionMatched.getId()
			);

		long totalCount = playlistRepository.countAllMatching(
			null,
			null,
			"비 오는 날"
		);

		assertThat(totalCount).isEqualTo(2L);
	}

	private User persistUser(String email, String name) {
		User user = new User(
			email,
			"encoded-password",
			name,
			null,
			UserRole.USER
		);

		return em.persistAndFlush(user);
	}

	private Playlist persistPlaylist(
		User owner,
		String title,
		String description
	) {
		Playlist playlist = new Playlist(
			owner,
			title,
			description
		);

		return em.persistAndFlush(playlist);
	}

	@Test
	@DisplayName("updatedAt cursor 형식이 잘못되면 INVALID_INPUT_VALUE 예외가 발생한다")
	void findAllByCursor_invalidUpdatedAtCursor_throwsException() {
		assertThatThrownBy(() ->
			playlistRepository.findAllByCursor(
				"invalid-cursor",
				UUID.randomUUID(),
				20,
				PlaylistSortBy.UPDATED_AT,
				SortDirection.DESCENDING,
				null,
				null,
				null
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);
	}

	@Test
	@DisplayName("subscribeCount cursor 형식이 잘못되면 INVALID_INPUT_VALUE 예외가 발생한다")
	void findAllByCursor_invalidSubscribeCountCursor_throwsException() {
		assertThatThrownBy(() ->
			playlistRepository.findAllByCursor(
				"not-a-number",
				UUID.randomUUID(),
				20,
				PlaylistSortBy.SUBSCRIBE_COUNT,
				SortDirection.DESCENDING,
				null,
				null,
				null
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);
	}

	@Test
	@DisplayName("구독자 수가 같아도 idAfter로 중복·누락 없이 다음 페이지를 조회한다")
	void findAllByCursor_sameSubscribeCount_usesIdAfterTieBreaker() {
		User owner = persistUser(
			"cursor-owner@mopl.io",
			"커서 테스트 작성자"
		);

		Playlist playlist1 = persistPlaylist(owner, "플레이리스트 1", "설명 1");
		Playlist playlist2 = persistPlaylist(owner, "플레이리스트 2", "설명 2");
		Playlist playlist3 = persistPlaylist(owner, "플레이리스트 3", "설명 3");
		Playlist playlist4 = persistPlaylist(owner, "플레이리스트 4", "설명 4");

		em.flush();
		em.clear();

		// 구독이 없으므로 모든 플레이리스트의 subscribeCount는 0으로 동일
		// 실제 페이지 크기는 2, repository에는 limit + 1인 3을 전달
		List<Playlist> firstFetch = playlistRepository.findAllByCursor(
			null,
			null,
			3,
			PlaylistSortBy.SUBSCRIBE_COUNT,
			SortDirection.DESCENDING,
			null,
			null,
			null
		);

		assertThat(firstFetch).hasSize(3);

		List<Playlist> firstPage = firstFetch.subList(0, 2);
		Playlist lastOfFirstPage = firstPage.get(1);

		List<Playlist> secondFetch = playlistRepository.findAllByCursor(
			"0",
			lastOfFirstPage.getId(),
			3,
			PlaylistSortBy.SUBSCRIBE_COUNT,
			SortDirection.DESCENDING,
			null,
			null,
			null
		);

		assertThat(secondFetch).hasSize(2);

		// 1페이지에서 미리 조회했던 look-ahead 데이터가
		// 다음 페이지의 첫 데이터가 되는지 확인
		assertThat(secondFetch.get(0).getId())
			.isEqualTo(firstFetch.get(2).getId());

		List<UUID> visitedIds = java.util.stream.Stream.concat(
				firstPage.stream(),
				secondFetch.stream()
			)
			.map(Playlist::getId)
			.toList();

		assertThat(visitedIds)
			.doesNotHaveDuplicates()
			.containsExactlyInAnyOrder(
				playlist1.getId(),
				playlist2.getId(),
				playlist3.getId(),
				playlist4.getId()
			);
	}

	@Test
	@DisplayName("구독자 수가 같아도 ASCENDING 정렬에서 idAfter로 중복·누락 없이 다음 페이지를 조회한다")
	void findAllByCursor_sameSubscribeCountAscending_usesIdAfterTieBreaker() {
		User owner = persistUser(
			"cursor-asc-owner@mopl.io",
			"ASC 커서 테스트 작성자"
		);

		Playlist playlist1 = persistPlaylist(owner, "ASC 플레이리스트 1", "설명 1");
		Playlist playlist2 = persistPlaylist(owner, "ASC 플레이리스트 2", "설명 2");
		Playlist playlist3 = persistPlaylist(owner, "ASC 플레이리스트 3", "설명 3");
		Playlist playlist4 = persistPlaylist(owner, "ASC 플레이리스트 4", "설명 4");

		em.flush();
		em.clear();

		List<Playlist> firstFetch = playlistRepository.findAllByCursor(
			null,
			null,
			3,
			PlaylistSortBy.SUBSCRIBE_COUNT,
			SortDirection.ASCENDING,
			null,
			null,
			null
		);

		assertThat(firstFetch).hasSize(3);

		List<Playlist> firstPage = firstFetch.subList(0, 2);
		Playlist lastOfFirstPage = firstPage.get(1);

		List<Playlist> secondFetch = playlistRepository.findAllByCursor(
			"0",
			lastOfFirstPage.getId(),
			3,
			PlaylistSortBy.SUBSCRIBE_COUNT,
			SortDirection.ASCENDING,
			null,
			null,
			null
		);

		assertThat(secondFetch).hasSize(2);

		assertThat(secondFetch.get(0).getId())
			.isEqualTo(firstFetch.get(2).getId());

		List<UUID> visitedIds = java.util.stream.Stream.concat(
				firstPage.stream(),
				secondFetch.stream()
			)
			.map(Playlist::getId)
			.toList();

		assertThat(visitedIds)
			.doesNotHaveDuplicates()
			.containsExactlyInAnyOrder(
				playlist1.getId(),
				playlist2.getId(),
				playlist3.getId(),
				playlist4.getId()
			);
	}
}