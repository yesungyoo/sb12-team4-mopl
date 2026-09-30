package com.mopl.playlist.ai.service;

import com.mopl.common.exception.MoplException;
import com.mopl.playlist.ai.config.AiPlaylistProperties;

import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Testcontainers
class AiPlaylistCandidateStoreTest {

	@Container
	static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;

	@BeforeAll
	static void setUpRedis() {
		connectionFactory = new LettuceConnectionFactory(
			REDIS.getHost(),
			REDIS.getMappedPort(6379)
		);
		connectionFactory.afterPropertiesSet();

		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
	}

	@BeforeEach
	void clearRedis() {
		connectionFactory
			.getConnection()
			.serverCommands()
			.flushDb();
	}

	@AfterAll
	static void tearDownRedis() {
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("검색 후보는 대화별로 분리하여 저장한다")
	void candidatesAreSeparatedByConversation() {
		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		UUID userId = UUID.randomUUID();
		UUID firstContentId = UUID.randomUUID();
		UUID secondContentId = UUID.randomUUID();

		candidateStore.saveCandidates(
			userId,
			"conversation-1",
			Set.of(firstContentId)
		);

		candidateStore.saveCandidates(
			userId,
			"conversation-2",
			Set.of(secondContentId)
		);

		assertTrue(candidateStore.containsAll(
			userId,
			"conversation-1",
			Set.of(firstContentId)
		));

		assertFalse(candidateStore.containsAll(
			userId,
			"conversation-1",
			Set.of(secondContentId)
		));

		assertTrue(candidateStore.containsAll(
			userId,
			"conversation-2",
			Set.of(secondContentId)
		));
	}

	@Test
	@DisplayName("같은 대화에서 검색한 콘텐츠 후보를 누적하여 저장한다")
	void candidatesAccumulateInSameConversation() {
		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		UUID userId = UUID.randomUUID();
		String conversationId = "conversation-1";
		UUID firstContentId = UUID.randomUUID();
		UUID secondContentId = UUID.randomUUID();

		candidateStore.saveCandidates(
			userId,
			conversationId,
			Set.of(firstContentId)
		);

		candidateStore.saveCandidates(
			userId,
			conversationId,
			Set.of(secondContentId)
		);

		assertTrue(candidateStore.containsAll(
			userId,
			conversationId,
			Set.of(firstContentId, secondContentId)
		));
	}

	@Test
	@DisplayName("같은 대화 ID를 사용해도 사용자별로 검색 후보를 분리한다")
	void candidatesAreSeparatedByUser() {
		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		UUID firstUserId = UUID.randomUUID();
		UUID secondUserId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		String conversationId = "conversation-1";

		candidateStore.saveCandidates(
			firstUserId,
			conversationId,
			Set.of(contentId)
		);

		assertTrue(candidateStore.containsAll(
			firstUserId,
			conversationId,
			Set.of(contentId)
		));

		assertFalse(candidateStore.containsAll(
			secondUserId,
			conversationId,
			Set.of(contentId)
		));
	}

	@Test
	@DisplayName("서버 인스턴스가 변경되어도 검색 후보가 유지되어야 한다")
	void candidatesShouldRemainAcrossStoreInstances() {
		UUID userId = UUID.randomUUID();
		String sessionId = "conversation-reproduction";
		UUID contentId = UUID.randomUUID();

		AiPlaylistCandidateStore firstInstance =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		firstInstance.saveCandidates(
			userId,
			sessionId,
			Set.of(contentId)
		);

		AiPlaylistCandidateStore secondInstance =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		assertTrue(secondInstance.containsAll(
			userId,
			sessionId,
			Set.of(contentId)
		));
	}

	@Test
	@DisplayName("Redis 장애 시 후보 검증은 503 도메인 예외로 변환한다")
	void candidateValidationFailsWithServiceUnavailableWhenRedisIsUnavailable() {
		StringRedisTemplate unavailableRedisTemplate =
			mock(StringRedisTemplate.class);

		when(unavailableRedisTemplate.opsForSet())
			.thenThrow(new DataAccessResourceFailureException(
				"Redis unavailable"
			));

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				unavailableRedisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		UUID userId = UUID.randomUUID();
		String sessionId = "redis-failure-reproduction";
		UUID contentId = UUID.randomUUID();

		MoplException exception = assertThrows(
			MoplException.class,
			() -> candidateStore.containsAll(
				userId,
				sessionId,
				Set.of(contentId)
			)
		);

		assertEquals(
			HttpStatus.SERVICE_UNAVAILABLE,
			exception.getErrorCode().getStatus()
		);
	}

	@Test
	@DisplayName("Redis 장애 시 후보 저장은 503 도메인 예외로 변환한다")
	void candidateSaveFailsWithServiceUnavailableWhenRedisIsUnavailable() {
		StringRedisTemplate unavailableRedisTemplate =
			mock(StringRedisTemplate.class);

		when(unavailableRedisTemplate.execute(
			ArgumentMatchers.<RedisScript<Long>>any(),
			anyList(),
			any(Object[].class)
		)).thenThrow(
			new DataAccessResourceFailureException("Redis unavailable")
		);

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				unavailableRedisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		MoplException exception = assertThrows(
			MoplException.class,
			() -> candidateStore.saveCandidates(
				UUID.randomUUID(),
				"redis-save-failure",
				Set.of(UUID.randomUUID())
			)
		);

		assertEquals(
			HttpStatus.SERVICE_UNAVAILABLE,
			exception.getErrorCode().getStatus()
		);
	}

	@Test
	@DisplayName("검색 후보를 저장하면 Candidate TTL이 설정된다")
	void candidatesHaveTtlAfterSave() {
		Duration candidateTtl = Duration.ofHours(6);

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(candidateTtl)
			);

		UUID userId = UUID.randomUUID();
		String sessionId = "ttl-test";
		UUID contentId = UUID.randomUUID();

		candidateStore.saveCandidates(
			userId,
			sessionId,
			Set.of(contentId)
		);

		String key =
			"ai:playlist:candidates:" + userId + ":" + sessionId;

		long ttlMillis = redisTemplate.getExpire(
			key,
			TimeUnit.MILLISECONDS
		);

		assertTrue(ttlMillis > 0);
		assertTrue(ttlMillis <= candidateTtl.toMillis());
	}

	@Test
	@DisplayName("같은 대화에서 추가 검색하면 Candidate TTL을 갱신한다")
	void candidateTtlIsRefreshedOnAdditionalSearch() {
		Duration candidateTtl = Duration.ofHours(6);

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(candidateTtl)
			);

		UUID userId = UUID.randomUUID();
		String sessionId = "sliding-ttl-test";
		UUID firstContentId = UUID.randomUUID();
		UUID secondContentId = UUID.randomUUID();

		candidateStore.saveCandidates(
			userId,
			sessionId,
			Set.of(firstContentId)
		);

		String key =
			"ai:playlist:candidates:" + userId + ":" + sessionId;

		// 시간이 지난 상태를 재현
		redisTemplate.expire(key, Duration.ofMinutes(1));

		long shortenedTtl = redisTemplate.getExpire(
			key,
			TimeUnit.MILLISECONDS
		);

		candidateStore.saveCandidates(
			userId,
			sessionId,
			Set.of(secondContentId)
		);

		long refreshedTtl = redisTemplate.getExpire(
			key,
			TimeUnit.MILLISECONDS
		);

		assertTrue(shortenedTtl <= Duration.ofMinutes(1).toMillis());
		assertTrue(refreshedTtl > Duration.ofHours(5).toMillis());
		assertTrue(refreshedTtl <= candidateTtl.toMillis());

		assertTrue(candidateStore.containsAll(
			userId,
			sessionId,
			Set.of(firstContentId, secondContentId)
		));
	}

	@Test
	@DisplayName("Candidate TTL이 만료되면 검색 후보에서 제거된다")
	void candidatesExpireAfterTtl() throws InterruptedException {
		Duration candidateTtl = Duration.ofMillis(200);

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(candidateTtl)
			);

		UUID userId = UUID.randomUUID();
		String sessionId = "expiration-test";
		UUID contentId = UUID.randomUUID();

		candidateStore.saveCandidates(
			userId,
			sessionId,
			Set.of(contentId)
		);

		assertTrue(candidateStore.containsAll(
			userId,
			sessionId,
			Set.of(contentId)
		));

		Thread.sleep(500);

		assertFalse(candidateStore.containsAll(
			userId,
			sessionId,
			Set.of(contentId)
		));
	}
}
