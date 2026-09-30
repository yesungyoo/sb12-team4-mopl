package com.mopl.playlist.ai.repository;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.core.common.enums.PlaylistAiMessageRole;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
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
	basePackageClasses = PlaylistAiMessageRepository.class
)
@Import(PlaylistAiMessageRepositoryTest.QuerydslTestConfig.class)
class PlaylistAiMessageRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@jakarta.annotation.Resource
	private PlaylistAiMessageRepository messageRepository;

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
	@DisplayName("AI 대화 메시지를 커서 기준으로 다음 페이지까지 중복 없이 조회한다")
	void findBySessionCursor() {
		User user = new User(
			"cursor-test@mopl.com",
			"password",
			"테스트 사용자",
			null,
			UserRole.USER
		);

		entityManager.persist(user);

		PlaylistAiSession session =
			new PlaylistAiSession(
				user,
				"커서 테스트"
			);

		entityManager.persist(session);

		LocalDateTime baseTime =
			LocalDateTime.of(2026, 9, 29, 10, 0);

		PlaylistAiMessage first =
			new PlaylistAiMessage(
				session,
				PlaylistAiMessageRole.USER,
				"첫 번째 메시지"
			);

		ReflectionTestUtils.setField(
			first,
			"createdAt",
			baseTime
		);

		entityManager.persist(first);
		entityManager.flush();

		PlaylistAiMessage second =
			new PlaylistAiMessage(
				session,
				PlaylistAiMessageRole.ASSISTANT,
				"두 번째 메시지"
			);

		ReflectionTestUtils.setField(
			second,
			"createdAt",
			baseTime.plusSeconds(1)
		);

		entityManager.persist(second);
		entityManager.flush();

		PlaylistAiMessage third =
			new PlaylistAiMessage(
				session,
				PlaylistAiMessageRole.USER,
				"세 번째 메시지"
			);

		ReflectionTestUtils.setField(
			third,
			"createdAt",
			baseTime.plusSeconds(2)
		);

		entityManager.persist(third);
		entityManager.flush();

		entityManager.clear();

		List<PlaylistAiMessage> firstPage =
			messageRepository.findBySessionCursor(
				session.getId(),
				null,
				null,
				2,
				false
			);

		assertThat(firstPage)
			.hasSize(2);

		assertThat(firstPage)
			.extracting(PlaylistAiMessage::getContent)
			.containsExactly(
				"세 번째 메시지",
				"두 번째 메시지"
			);

		PlaylistAiMessage lastMessage =
			firstPage.getLast();

		String cursor = lastMessage.getCreatedAt()
			.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

		UUID idAfter = lastMessage.getId();

		List<PlaylistAiMessage> secondPage =
			messageRepository.findBySessionCursor(
				session.getId(),
				cursor,
				idAfter,
				2,
				false
			);

		assertThat(secondPage)
			.hasSize(1);

		assertThat(secondPage.getFirst().getContent())
			.isEqualTo("첫 번째 메시지");

		assertThat(secondPage)
			.extracting(PlaylistAiMessage::getId)
			.doesNotContainAnyElementsOf(
				firstPage.stream()
					.map(PlaylistAiMessage::getId)
					.toList()
			);
	}

	@Test
	@DisplayName("cursor 형식이 잘못되면 INVALID_INPUT_VALUE 예외가 발생한다")
	void findBySessionCursor_invalidCursor() {
		assertThatThrownBy(() ->
			messageRepository.findBySessionCursor(
				UUID.randomUUID(),
				"invalid-cursor",
				UUID.randomUUID(),
				20,
				false
			)
		)
			.isInstanceOfSatisfying(MoplException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE)
			);
	}

	@Test
	@DisplayName("createdAt이 같으면 id를 기준으로 다음 페이지를 조회한다")
	void findBySessionCursor_sameCreatedAtUsesIdTieBreaker() {
		User user = new User(
			"tie-breaker-test@mopl.com",
			"password",
			"테스트 사용자",
			null,
			UserRole.USER
		);
		entityManager.persist(user);

		PlaylistAiSession session = new PlaylistAiSession(
			user,
			"동일 시간 테스트"
		);
		entityManager.persist(session);

		LocalDateTime sameCreatedAt =
			LocalDateTime.of(2026, 9, 29, 12, 0);

		PlaylistAiMessage first = new PlaylistAiMessage(
			session,
			PlaylistAiMessageRole.USER,
			"첫 번째"
		);

		PlaylistAiMessage second = new PlaylistAiMessage(
			session,
			PlaylistAiMessageRole.ASSISTANT,
			"두 번째"
		);

		ReflectionTestUtils.setField(
			first,
			"createdAt",
			sameCreatedAt
		);

		ReflectionTestUtils.setField(
			second,
			"createdAt",
			sameCreatedAt
		);

		entityManager.persist(first);
		entityManager.persist(second);
		entityManager.flush();
		entityManager.clear();

		List<PlaylistAiMessage> firstPage =
			messageRepository.findBySessionCursor(
				session.getId(),
				null,
				null,
				1,
				false
			);

		assertThat(firstPage).hasSize(1);

		PlaylistAiMessage lastMessage = firstPage.getFirst();

		List<PlaylistAiMessage> secondPage =
			messageRepository.findBySessionCursor(
				session.getId(),
				lastMessage.getCreatedAt()
					.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
				lastMessage.getId(),
				1,
				false
			);

		assertThat(secondPage).hasSize(1);

		assertThat(secondPage.getFirst().getId())
			.isNotEqualTo(lastMessage.getId());
	}

	@Test
	@DisplayName("ASCENDING 정렬로 AI 대화 메시지를 다음 페이지까지 조회한다")
	void findBySessionCursor_ascending() {
		User user = new User(
			"ascending-test@mopl.com",
			"password",
			"테스트 사용자",
			null,
			UserRole.USER
		);
		entityManager.persist(user);

		PlaylistAiSession session = new PlaylistAiSession(
			user,
			"ASCENDING 테스트"
		);
		entityManager.persist(session);

		LocalDateTime baseTime =
			LocalDateTime.of(2026, 9, 29, 10, 0);

		PlaylistAiMessage first = new PlaylistAiMessage(
			session,
			PlaylistAiMessageRole.USER,
			"첫 번째 메시지"
		);
		ReflectionTestUtils.setField(
			first,
			"createdAt",
			baseTime
		);
		entityManager.persist(first);
		entityManager.flush();

		PlaylistAiMessage second = new PlaylistAiMessage(
			session,
			PlaylistAiMessageRole.ASSISTANT,
			"두 번째 메시지"
		);
		ReflectionTestUtils.setField(
			second,
			"createdAt",
			baseTime.plusSeconds(1)
		);
		entityManager.persist(second);
		entityManager.flush();

		PlaylistAiMessage third = new PlaylistAiMessage(
			session,
			PlaylistAiMessageRole.USER,
			"세 번째 메시지"
		);
		ReflectionTestUtils.setField(
			third,
			"createdAt",
			baseTime.plusSeconds(2)
		);
		entityManager.persist(third);
		entityManager.flush();

		entityManager.clear();

		List<PlaylistAiMessage> firstPage =
			messageRepository.findBySessionCursor(
				session.getId(),
				null,
				null,
				2,
				true
			);

		assertThat(firstPage)
			.extracting(PlaylistAiMessage::getContent)
			.containsExactly(
				"첫 번째 메시지",
				"두 번째 메시지"
			);

		PlaylistAiMessage lastMessage = firstPage.getLast();

		String cursor = lastMessage.getCreatedAt()
			.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

		List<PlaylistAiMessage> secondPage =
			messageRepository.findBySessionCursor(
				session.getId(),
				cursor,
				lastMessage.getId(),
				2,
				true
			);

		assertThat(secondPage)
			.extracting(PlaylistAiMessage::getContent)
			.containsExactly("세 번째 메시지");

		assertThat(secondPage)
			.extracting(PlaylistAiMessage::getId)
			.doesNotContainAnyElementsOf(
				firstPage.stream()
					.map(PlaylistAiMessage::getId)
					.toList()
			);
	}
}