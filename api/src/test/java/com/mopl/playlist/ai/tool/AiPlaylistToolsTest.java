package com.mopl.playlist.ai.tool;

import com.mopl.playlist.ai.service.AiPlaylistCandidateStore;
import com.mopl.content.service.ContentService;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.review.service.ReviewService;
import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.content.search.condition.SemanticCandidateCondition;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.content.search.service.SemanticCandidateSearchService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.service.PlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.playlist.PlaylistAiContentNotSearchedException;
import com.mopl.common.exception.playlist.PlaylistAiCandidateStoreUnavailableException;
import com.mopl.playlist.ai.config.AiPlaylistProperties;
import com.mopl.recommendation.service.RecommendationService;
import com.mopl.recommendation.dto.RecommendationItem;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Testcontainers
class AiPlaylistToolsTest {

	@Container
	static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;

	private PlaylistService playlistService;
	private AiPlaylistTools aiPlaylistTools;
	private AiPlaylistCandidateStore candidateStore;
	private RecommendationService recommendationService;

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
	void setUp() {
		connectionFactory
			.getConnection()
			.serverCommands()
			.flushDb();

		playlistService = mock(PlaylistService.class);

		recommendationService = mock(RecommendationService.class);

		SemanticCandidateSearchService semanticCandidateSearchService =
			mock(SemanticCandidateSearchService.class);

		candidateStore = new AiPlaylistCandidateStore(
			redisTemplate,
			new AiPlaylistProperties(Duration.ofHours(6))
		);

		aiPlaylistTools = new AiPlaylistTools(
			playlistService,
			semanticCandidateSearchService,
			candidateStore,
			recommendationService,
			mock(ContentService.class),
			mock(ContentTagRepository.class),
			mock(ReviewRepository.class),
			mock(ReviewService.class),
			mock(WatchingSessionService.class),
			mock(ContentViewRepository.class)
		);
	}

	@AfterAll
	static void tearDownRedis() {
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("검색 후보에 없는 콘텐츠로 플레이리스트를 생성하면 예외가 발생한다")
	void createPlaylistWithUnknownContentFails() {
		UUID currentUserId = UUID.randomUUID();
		UUID unknownContentId = UUID.randomUUID();
		String sessionId = "test-session";

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));

		assertThrows(
			PlaylistAiContentNotSearchedException.class,
			() -> aiPlaylistTools.createPlaylist(
				"테스트 플레이리스트",
				"테스트 설명",
				List.of(unknownContentId),
				toolContext
			)
		);

		verifyNoInteractions(playlistService);
	}

	@Test
	@DisplayName("검색 후보에 포함된 콘텐츠로 플레이리스트를 생성하면 서비스가 호출된다")
	void createPlaylistWithCandidateContentSucceeds() {
		UUID currentUserId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		String sessionId = "test-session";

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		candidateStore.saveCandidates(
			currentUserId,
			sessionId,
			Set.of(contentId)
		);

		AiPlaylistTools tools = new AiPlaylistTools(
			playlistService,
			mock(SemanticCandidateSearchService.class),
			candidateStore,
			recommendationService,
			mock(ContentService.class),
			mock(ContentTagRepository.class),
			mock(ReviewRepository.class),
			mock(ReviewService.class),
			mock(WatchingSessionService.class),
			mock(ContentViewRepository.class)
		);

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));

		PlaylistResponse playlistResponse = mock(PlaylistResponse.class);

		when(playlistService.createPlaylistWithContents(
			eq(currentUserId),
			any(PlaylistCreateRequest.class),
			eq(List.of(contentId))
		)).thenReturn(playlistResponse);

		tools.createPlaylist(
			"테스트 플레이리스트",
			"테스트 설명",
			List.of(contentId),
			toolContext
		);

		verify(playlistService).createPlaylistWithContents(
			eq(currentUserId),
			any(PlaylistCreateRequest.class),
			eq(List.of(contentId))
		);
	}

	@Test
	@DisplayName("콘텐츠를 검색하면 검색 결과의 ID를 세션별 후보로 저장한다")
	void searchContentsSavesCandidatesBySession() {
		UUID contentId = UUID.randomUUID();
		UUID currentUserId = UUID.randomUUID();
		String sessionId = "test-session";

		ContentCandidate candidate = mock(ContentCandidate.class);
		when(candidate.contentId()).thenReturn(contentId);

		SemanticCandidateSearchService searchService =
			mock(SemanticCandidateSearchService.class);

		when(searchService.search(
			eq("주말에 볼 영화"),
			any(SemanticCandidateCondition.class),
			eq(10)
		)).thenReturn(List.of(candidate));

		AiPlaylistCandidateStore candidateStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		AiPlaylistTools tools = new AiPlaylistTools(
			playlistService,
			searchService,
			candidateStore,
			recommendationService,
			mock(ContentService.class),
			mock(ContentTagRepository.class),
			mock(ReviewRepository.class),
			mock(ReviewService.class),
			mock(WatchingSessionService.class),
			mock(ContentViewRepository.class)
		);

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));
		List<ContentCandidate> result = tools.searchContents(
			"주말에 볼 영화",
			ContentType.MOVIE,
			toolContext
		);

		assertEquals(List.of(candidate), result);
		assertTrue(candidateStore.containsAll(
			currentUserId,
			sessionId,
			Set.of(contentId)
		));

		verify(searchService).search(
			eq("주말에 볼 영화"),
			any(SemanticCandidateCondition.class),
			eq(10)
		);
	}

	@Test
	@DisplayName("개인화 추천 결과의 ID를 세션별 후보로 저장한다")
	void recommendContentsForUserSavesCandidatesBySession() {
		UUID currentUserId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		String sessionId = "test-session";

		RecommendationItem recommendation = mock(RecommendationItem.class);
		when(recommendation.contentId()).thenReturn(contentId);

		when(recommendationService.getRecommendations(currentUserId))
			.thenReturn(List.of(recommendation));

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));

		List<RecommendationItem> result =
			aiPlaylistTools.recommendContentsForUser(toolContext);

		assertEquals(List.of(recommendation), result);

		assertTrue(candidateStore.containsAll(
			currentUserId,
			sessionId,
			Set.of(contentId)
		));

		verify(recommendationService)
			.getRecommendations(currentUserId);
	}

	@Test
	@DisplayName("콘텐츠가 없으면 플레이리스트를 생성하지 않는다")
	void createPlaylistWithEmptyContentsFails() {
		UUID currentUserId = UUID.randomUUID();
		String sessionId = "test-session";

		// 검색을 한 상태를 먼저 만든다.
		candidateStore.saveCandidates(
			currentUserId,
			sessionId,
			Set.of(UUID.randomUUID())
		);

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));

		MoplException exception = assertThrows(
			MoplException.class,
			() -> aiPlaylistTools.createPlaylist(
				"테스트 플레이리스트",
				"테스트 설명",
				List.of(),
				toolContext
			)
		);

		assertEquals(
			CommonErrorCode.INVALID_INPUT_VALUE,
			exception.getErrorCode()
		);

		verifyNoInteractions(playlistService);
	}

	@Test
	@DisplayName("검색한 후보는 서버 인스턴스가 변경되어도 플레이리스트 생성에 사용할 수 있다")
	void searchedCandidatesCanBeUsedAfterInstanceChange() {
		UUID currentUserId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		String sessionId = "instance-change-session";

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));

		ContentCandidate candidate = mock(ContentCandidate.class);
		when(candidate.contentId()).thenReturn(contentId);

		SemanticCandidateSearchService searchService =
			mock(SemanticCandidateSearchService.class);

		when(searchService.search(
			eq("주말에 볼 영화"),
			any(SemanticCandidateCondition.class),
			eq(10)
		)).thenReturn(List.of(candidate));

		// Server A
		AiPlaylistCandidateStore firstStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		AiPlaylistTools firstInstance = new AiPlaylistTools(
			playlistService,
			searchService,
			firstStore,
			recommendationService,
			mock(ContentService.class),
			mock(ContentTagRepository.class),
			mock(ReviewRepository.class),
			mock(ReviewService.class),
			mock(WatchingSessionService.class),
			mock(ContentViewRepository.class)
		);

		// Server A에서 콘텐츠 검색 → 후보 저장
		firstInstance.searchContents(
			"주말에 볼 영화",
			ContentType.MOVIE,
			toolContext
		);

		assertTrue(firstStore.containsAll(
			currentUserId,
			sessionId,
			Set.of(contentId)
		));

		// Server B 또는 서버 재시작 후 새 CandidateStore를 모사
		AiPlaylistCandidateStore secondStore =
			new AiPlaylistCandidateStore(
				redisTemplate,
				new AiPlaylistProperties(Duration.ofHours(6))
			);

		AiPlaylistTools secondInstance = new AiPlaylistTools(
			playlistService,
			searchService,
			secondStore,
			recommendationService,
			mock(ContentService.class),
			mock(ContentTagRepository.class),
			mock(ReviewRepository.class),
			mock(ReviewService.class),
			mock(WatchingSessionService.class),
			mock(ContentViewRepository.class)
		);

		PlaylistResponse playlistResponse =
			mock(PlaylistResponse.class);

		when(playlistService.createPlaylistWithContents(
			eq(currentUserId),
			any(PlaylistCreateRequest.class),
			eq(List.of(contentId))
		)).thenReturn(playlistResponse);

		// 검색은 이미 완료됐으므로 인스턴스가 변경되어도 생성되어야 함
		assertDoesNotThrow(() ->
			secondInstance.createPlaylist(
				"주말 영화",
				"AI 추천 영화",
				List.of(contentId),
				toolContext
			)
		);

		verify(playlistService).createPlaylistWithContents(
			eq(currentUserId),
			any(PlaylistCreateRequest.class),
			eq(List.of(contentId))
		);
	}

	@Test
	@DisplayName("후보 저장소 장애 시 플레이리스트 생성을 수행하지 않는다")
	void createPlaylistDoesNotProceedWhenCandidateStoreIsUnavailable() {
		UUID currentUserId = UUID.randomUUID();
		UUID contentId = UUID.randomUUID();
		String sessionId = "redis-failure-session";

		ToolContext toolContext = new ToolContext(Map.of(
			"currentUserId", currentUserId,
			"sessionId", sessionId
		));

		AiPlaylistCandidateStore unavailableCandidateStore =
			mock(AiPlaylistCandidateStore.class);

		when(unavailableCandidateStore.containsAll(
			eq(currentUserId),
			eq(sessionId),
			eq(Set.of(contentId))
		)).thenThrow(
			new PlaylistAiCandidateStoreUnavailableException()
		);

		AiPlaylistTools tools = new AiPlaylistTools(
			playlistService,
			mock(SemanticCandidateSearchService.class),
			unavailableCandidateStore,
			recommendationService,
			mock(ContentService.class),
			mock(ContentTagRepository.class),
			mock(ReviewRepository.class),
			mock(ReviewService.class),
			mock(WatchingSessionService.class),
			mock(ContentViewRepository.class)
		);

		assertThrows(
			PlaylistAiCandidateStoreUnavailableException.class,
			() -> tools.createPlaylist(
				"테스트 플레이리스트",
				"테스트 설명",
				List.of(contentId),
				toolContext
			)
		);

		verifyNoInteractions(playlistService);
	}
}
