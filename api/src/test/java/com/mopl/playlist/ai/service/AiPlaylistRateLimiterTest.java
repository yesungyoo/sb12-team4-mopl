package com.mopl.playlist.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.RetryAfterProvider;
import com.mopl.common.exception.playlist.PlaylistErrorCode;
import com.mopl.playlist.ai.config.AiPlaylistRateLimitProperties;
import com.mopl.common.exception.playlist.PlaylistAiRateLimitExceededException;
import com.mopl.common.exception.playlist.PlaylistAiRateLimitStoreUnavailableException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Duration;
import java.util.UUID;

@Testcontainers
class AiPlaylistRateLimiterTest {

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
	@DisplayName("같은 사용자는 제한 횟수까지 AI 요청을 사용할 수 있다")
	void allowsRequestsUpToLimit() {
		AiPlaylistRateLimiter rateLimiter = createRateLimiter();
		UUID userId = UUID.randomUUID();

		for (int i = 0; i < 10; i++) {
			assertDoesNotThrow(() -> rateLimiter.check(userId));
		}
	}

	@Test
	@DisplayName("제한 횟수를 초과하면 429 예외와 Retry-After를 반환한다")
	void rejectsRequestOverLimit() {
		AiPlaylistRateLimiter rateLimiter = createRateLimiter();
		UUID userId = UUID.randomUUID();

		for (int i = 0; i < 10; i++) {
			rateLimiter.check(userId);
		}

		MoplException exception = assertThrows(
			MoplException.class,
			() -> rateLimiter.check(userId)
		);

		assertEquals(
			PlaylistErrorCode.PLAYLIST_AI_RATE_LIMIT_EXCEEDED,
			exception.getErrorCode()
		);

		assertEquals(
			429,
			exception.getErrorCode().getStatus().value()
		);

		RetryAfterProvider retryAfterProvider =
			assertInstanceOf(
				RetryAfterProvider.class,
				exception
			);

		long retryAfterSeconds =
			retryAfterProvider.getRetryAfterSeconds();

		assertTrue(retryAfterSeconds >= 1);
		assertTrue(retryAfterSeconds <= 60);
	}

	@Test
	@DisplayName("사용자별로 AI 요청 횟수를 독립적으로 제한한다")
	void separatesRateLimitByUser() {
		AiPlaylistRateLimiter rateLimiter = createRateLimiter();

		UUID firstUserId = UUID.randomUUID();
		UUID secondUserId = UUID.randomUUID();

		for (int i = 0; i < 10; i++) {
			rateLimiter.check(firstUserId);
		}

		assertThrows(
			MoplException.class,
			() -> rateLimiter.check(firstUserId)
		);

		assertDoesNotThrow(
			() -> rateLimiter.check(secondUserId)
		);
	}

	@Test
	@DisplayName("서로 다른 RateLimiter 인스턴스가 Redis 카운터를 공유한다")
	void sharesRateLimitAcrossInstances() {
		AiPlaylistRateLimiter firstRateLimiter = createRateLimiter();
		AiPlaylistRateLimiter secondRateLimiter = createRateLimiter();

		UUID userId = UUID.randomUUID();

		for (int i = 0; i < 5; i++) {
			firstRateLimiter.check(userId);
		}

		for (int i = 0; i < 5; i++) {
			secondRateLimiter.check(userId);
		}

		MoplException exception = assertThrows(
			MoplException.class,
			() -> firstRateLimiter.check(userId)
		);

		assertEquals(
			PlaylistErrorCode.PLAYLIST_AI_RATE_LIMIT_EXCEEDED,
			exception.getErrorCode()
		);
	}

	@Test
	@DisplayName("Rate Limit 윈도우가 만료되면 다시 요청할 수 있다")
	void allowsRequestAfterWindowExpires() throws Exception {
		AiPlaylistRateLimiter rateLimiter =
			new AiPlaylistRateLimiter(
				redisTemplate,
				new AiPlaylistRateLimitProperties(
					2,
					Duration.ofMillis(500)
				)
			);

		UUID userId = UUID.randomUUID();

		rateLimiter.check(userId);
		rateLimiter.check(userId);

		assertThrows(
			PlaylistAiRateLimitExceededException.class,
			() -> rateLimiter.check(userId)
		);

		Thread.sleep(700);

		assertDoesNotThrow(
			() -> rateLimiter.check(userId)
		);
	}

	@Test
	@DisplayName("동시에 30건을 요청해도 정확히 10건만 허용한다")
	void limitsConcurrentRequestsAtomically() throws Exception {
		AiPlaylistRateLimiter rateLimiter = createRateLimiter();
		UUID userId = UUID.randomUUID();

		int requestCount = 30;

		ExecutorService executor =
			Executors.newFixedThreadPool(requestCount);

		CountDownLatch ready =
			new CountDownLatch(requestCount);

		CountDownLatch start =
			new CountDownLatch(1);

		List<Future<Boolean>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < requestCount; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();

					try {
						rateLimiter.check(userId);
						return true;
					} catch (PlaylistAiRateLimitExceededException e) {
						return false;
					}
				}));
			}

			ready.await();
			start.countDown();

			int allowed = 0;
			int rejected = 0;

			for (Future<Boolean> future : futures) {
				if (future.get()) {
					allowed++;
				} else {
					rejected++;
				}
			}

			assertEquals(10, allowed);
			assertEquals(20, rejected);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("Redis 장애 시 AI 요청을 허용하지 않고 503을 반환한다")
	void rejectsRequestWhenRedisIsUnavailable() {
		StringRedisTemplate unavailableRedisTemplate =
			mock(StringRedisTemplate.class);

		when(unavailableRedisTemplate.execute(
			any(),
			anyList(),
			any()
		)).thenThrow(
			new DataAccessResourceFailureException("Redis unavailable")
		);

		AiPlaylistRateLimiter rateLimiter =
			new AiPlaylistRateLimiter(
				unavailableRedisTemplate,
				new AiPlaylistRateLimitProperties(
					10,
					Duration.ofMinutes(1)
				)
			);

		PlaylistAiRateLimitStoreUnavailableException exception =
			assertThrows(
				PlaylistAiRateLimitStoreUnavailableException.class,
				() -> rateLimiter.check(UUID.randomUUID())
			);

		assertEquals(
			PlaylistErrorCode.PLAYLIST_AI_RATE_LIMIT_STORE_UNAVAILABLE,
			exception.getErrorCode()
		);

		assertEquals(
			503,
			exception.getErrorCode().getStatus().value()
		);
	}

	private AiPlaylistRateLimiter createRateLimiter() {
		return new AiPlaylistRateLimiter(
			redisTemplate,
			new AiPlaylistRateLimitProperties(
				10,
				Duration.ofMinutes(1)
			)
		);
	}
}