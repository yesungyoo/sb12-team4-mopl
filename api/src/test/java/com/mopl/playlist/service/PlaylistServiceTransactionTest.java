package com.mopl.playlist.service;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.user.entity.User;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
@Import({
	PlaylistService.class,
	PlaylistServiceTransactionTest.QuerydslTestConfig.class
})
class PlaylistServiceTransactionTest {

	@TestConfiguration
	static class QuerydslTestConfig {

		@PersistenceContext
		private EntityManager entityManager;

		@Bean
		JPAQueryFactory jpaQueryFactory() {
			return new JPAQueryFactory(entityManager);
		}

		@Bean
		Validator validator() {
			return Validation.buildDefaultValidatorFactory()
				.getValidator();
		}
	}

	@Autowired
	private PlaylistService playlistService;

	@Autowired
	private PlaylistRepository playlistRepository;

	@Autowired
	private PlaylistContentRepository playlistContentRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("콘텐츠 추가 중 실패하면 플레이리스트 생성 전체가 롤백된다")
	void createPlaylistWithContentsRollsBackWhenContentAdditionFails() {

		TransactionTemplate transactionTemplate =
			new TransactionTemplate(transactionManager);

		UUID[] userId = new UUID[1];
		UUID[] contentId = new UUID[1];

		// Given: 준비 데이터는 먼저 별도 트랜잭션으로 저장
		transactionTemplate.executeWithoutResult(status -> {
			User user = new User(
				"rollback-test@mopl.io",
				"encoded-password",
				"롤백 테스트 사용자",
				null,
				UserRole.USER
			);

			entityManager.persist(user);

			Content content = new Content(
				ContentType.MOVIE,
				"정상 콘텐츠",
				"테스트 콘텐츠",
				null,
				ExternalSource.TMDB,
				"rollback-content",
				null,
				null,
				null,
				null
			);

			entityManager.persist(content);
			entityManager.flush();

			userId[0] = user.getId();
			contentId[0] = content.getId();
		});

		long playlistCountBefore = playlistRepository.count();
		long playlistContentCountBefore = playlistContentRepository.count();

		UUID invalidContentId = UUID.randomUUID();

		// When
		assertThatThrownBy(() ->
			playlistService.createPlaylistWithContents(
				userId[0],
				new PlaylistCreateRequest(
					"롤백 테스트",
					"롤백 테스트 설명"
				),
				List.of(
					contentId[0],      // 여기까지는 추가 성공
					invalidContentId   // 여기서 실패
				)
			)
		).isInstanceOf(ContentNotFoundException.class);

		// Then: 서비스 트랜잭션은 이미 종료되어 rollback된 상태
		assertThat(playlistRepository.count())
			.isEqualTo(playlistCountBefore);

		assertThat(playlistContentRepository.count())
			.isEqualTo(playlistContentCountBefore);
	}

	@Test
	@DisplayName("AI 생성 경로에서도 잘못된 플레이리스트 생성 요청은 검증된다")
	void createPlaylistWithContentsValidatesCreateRequest() {
		PlaylistCreateRequest invalidRequest =
			new PlaylistCreateRequest("", "테스트 설명");

		assertThatThrownBy(() ->
			playlistService.createPlaylistWithContents(
				UUID.randomUUID(),
				invalidRequest,
				List.of(UUID.randomUUID())
			)
		).isInstanceOf(MoplException.class);
	}
}