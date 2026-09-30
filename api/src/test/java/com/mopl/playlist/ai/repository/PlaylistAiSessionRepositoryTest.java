package com.mopl.playlist.ai.repository;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import com.mopl.core.domain.user.entity.User;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
@EnableJpaRepositories(
	basePackageClasses = PlaylistAiSessionRepository.class
)
@Import(PlaylistAiSessionRepositoryTest.QuerydslTestConfig.class)
class PlaylistAiSessionRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@jakarta.annotation.Resource
	private PlaylistAiSessionRepository sessionRepository;

	@TestConfiguration
	static class QuerydslTestConfig {

		@PersistenceContext
		private EntityManager entityManager;

		@Bean
		JPAQueryFactory jpaQueryFactory() {
			return new JPAQueryFactory(entityManager);
		}
	}

	@Test
	@DisplayName("AI 플레이리스트 대화 세션을 커서 기준으로 다음 페이지까지 중복 없이 조회한다")
	void findByUserCursor() {
		User user = createUser("session-cursor-test@mopl.com");

		LocalDateTime baseTime =
			LocalDateTime.of(2026, 9, 29, 10, 0);

		createSession(user, "첫 번째 대화", baseTime);

		createSession(user, "두 번째 대화", baseTime.plusSeconds(1));

		createSession(user, "세 번째 대화", baseTime.plusSeconds(2));

		entityManager.flush();
		entityManager.clear();

		List<PlaylistAiSession> firstPage =
			sessionRepository.findByUserCursor(
				user.getId(),
				null,
				null,
				2,
				false
			);

		assertThat(firstPage)
			.extracting(PlaylistAiSession::getTitle)
			.containsExactly(
				"세 번째 대화",
				"두 번째 대화"
			);

		PlaylistAiSession lastSession = firstPage.getLast();

		String cursor = lastSession.getUpdatedAt()
			.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

		List<PlaylistAiSession> secondPage =
			sessionRepository.findByUserCursor(
				user.getId(),
				cursor,
				lastSession.getId(),
				2,
				false
			);

		assertThat(secondPage)
			.extracting(PlaylistAiSession::getTitle)
			.containsExactly("첫 번째 대화");

		assertThat(secondPage)
			.extracting(PlaylistAiSession::getId)
			.doesNotContainAnyElementsOf(
				firstPage.stream()
					.map(PlaylistAiSession::getId)
					.toList()
			);
	}

	@Test
	@DisplayName("cursor 형식이 잘못되면 INVALID_INPUT_VALUE 예외가 발생한다")
	void findByUserCursor_invalidCursor() {
		assertThatThrownBy(() ->
			sessionRepository.findByUserCursor(
				UUID.randomUUID(),
				"invalid-cursor",
				UUID.randomUUID(),
				20,
				false
			)
		)
			.isInstanceOfSatisfying(
				MoplException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(
						CommonErrorCode.INVALID_INPUT_VALUE
					)
			);
	}

	@Test
	@DisplayName("updatedAt이 같으면 id를 기준으로 다음 페이지를 조회한다")
	void findByUserCursor_sameUpdatedAtUsesIdTieBreaker() {
		User user = createUser("session-tie-breaker@mopl.com");

		LocalDateTime sameUpdatedAt =
			LocalDateTime.of(2026, 9, 29, 12, 0);

		createSession(
			user,
			"첫 번째 대화",
			sameUpdatedAt
		);

		createSession(
			user,
			"두 번째 대화",
			sameUpdatedAt
		);

		entityManager.flush();
		entityManager.clear();

		List<PlaylistAiSession> firstPage =
			sessionRepository.findByUserCursor(
				user.getId(),
				null,
				null,
				1,
				false
			);

		assertThat(firstPage).hasSize(1);

		PlaylistAiSession lastSession = firstPage.getFirst();

		List<PlaylistAiSession> secondPage =
			sessionRepository.findByUserCursor(
				user.getId(),
				lastSession.getUpdatedAt()
					.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
				lastSession.getId(),
				1,
				false
			);

		assertThat(secondPage).hasSize(1);

		assertThat(secondPage.getFirst().getId())
			.isNotEqualTo(lastSession.getId());
	}

	@Test
	@DisplayName("ASCENDING 정렬로 AI 대화 세션을 다음 페이지까지 조회한다")
	void findByUserCursor_ascending() {
		User user = createUser("session-ascending@mopl.com");

		LocalDateTime baseTime =
			LocalDateTime.of(2026, 9, 29, 10, 0);

		createSession(user, "첫 번째 대화", baseTime);
		createSession(user, "두 번째 대화", baseTime.plusSeconds(1));
		createSession(user, "세 번째 대화", baseTime.plusSeconds(2));

		entityManager.flush();
		entityManager.clear();

		List<PlaylistAiSession> firstPage =
			sessionRepository.findByUserCursor(
				user.getId(),
				null,
				null,
				2,
				true
			);

		assertThat(firstPage)
			.extracting(PlaylistAiSession::getTitle)
			.containsExactly(
				"첫 번째 대화",
				"두 번째 대화"
			);

		PlaylistAiSession lastSession = firstPage.getLast();

		List<PlaylistAiSession> secondPage =
			sessionRepository.findByUserCursor(
				user.getId(),
				lastSession.getUpdatedAt()
					.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
				lastSession.getId(),
				2,
				true
			);

		assertThat(secondPage)
			.extracting(PlaylistAiSession::getTitle)
			.containsExactly("세 번째 대화");
	}

	private User createUser(String email) {
		User user = new User(
			email,
			"password",
			"테스트 사용자",
			null,
			UserRole.USER
		);

		entityManager.persist(user);

		return user;
	}

	private PlaylistAiSession createSession(
		User user,
		String title,
		LocalDateTime updatedAt
	) {
		PlaylistAiSession session =
			new PlaylistAiSession(user, title);

		ReflectionTestUtils.setField(
			session,
			"updatedAt",
			updatedAt
		);

		entityManager.persist(session);

		return session;
	}
}