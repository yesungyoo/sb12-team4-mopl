package com.mopl.playlist.ai.tool;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.dto.ContentSortDirection;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.core.domain.content.id.ContentViewId;
import java.time.LocalDateTime;
import com.mopl.content.service.ContentService;
import com.mopl.content.search.condition.ContentTagCondition;
import com.mopl.content.search.dto.ContentTagDto;
import com.mopl.review.dto.ReviewListResponse;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.review.service.ReviewService;
import com.mopl.watchingsession.dto.WatchingSessionResponse;
import com.mopl.watchingsession.service.WatchingSessionService;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.playlist.PlaylistAiContentNotSearchedException;
import com.mopl.playlist.ai.service.AiPlaylistCandidateStore;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.service.PlaylistService;
import org.springframework.ai.chat.model.ToolContext;
import com.mopl.content.search.service.SemanticCandidateSearchService;
import com.mopl.content.search.condition.SemanticCandidateCondition;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.core.common.enums.ContentType;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.service.RecommendationService;


import java.util.UUID;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AiPlaylistTools {

	private final PlaylistService playlistService;
	private final SemanticCandidateSearchService semanticCandidateSearchService;
	private final AiPlaylistCandidateStore aiPlaylistCandidateStore;
	private final RecommendationService recommendationService;
	private final ContentService contentService;
	private final ContentTagRepository contentTagRepository;
	private final ReviewRepository reviewRepository;
	private final ReviewService reviewService;
	private final WatchingSessionService watchingSessionService;
	private final ContentViewRepository contentViewRepository;

	@Tool(description = """
    사용자가 원하는 조건에 맞는 MOPL의 실제 콘텐츠를 찾을 때 사용합니다.
    자연어 분위기/상황 검색은 기본 SEMANTIC 모드로 queryText를 전달하세요.
    제목/설명 키워드 검색, 목록 탐색, 정렬, 커서 조회는 KEYWORD 모드를 사용하세요.
    type은 사용자가 명시한 실제 콘텐츠 종류만 지정하세요.
    KEYWORD의 tagsIn은 태그 값 목록이며, 기존 검색처럼 그중 하나가 일치하는 조건입니다.
    SEMANTIC의 semanticTags는 실제 tag/value 쌍이며 모든 조건이 일치해야 합니다.
    모르는 태그 키/값은 만들어내지 마세요. 필요하면 getContent로 확인하세요.
    KEYWORD 정렬은 createdAt, watcherCount, rate와 ASCENDING/DESCENDING만 지원합니다.
    watcherCount는 누적 시청 사용자 통계이며 현재 시청 중인 인원은 getWatchingSessions로 조회하세요.
    다음 KEYWORD 페이지는 반환된 nextCursor와 nextIdAfter를 cursor/idAfter에 함께 전달하세요.
    SEMANTIC은 유사도 순서이며 커서/별도 정렬/tagsIn을 지원하지 않습니다.
    KEYWORD 결과의 id와 SEMANTIC 결과의 contentId는 모두 생성에 사용할 콘텐츠 UUID입니다.
    """)
	public ContentSearchResult searchContents(
		@ToolParam(required = false) String queryText,
		@ToolParam(required = false) ContentType type,
		@ToolParam(required = false) ContentSearchOptions options,
		ToolContext toolContext
	) {
		SearchMode mode = options == null || options.mode() == null
			? SearchMode.SEMANTIC : options.mode();
		int limit = pageLimit(options == null ? null : options.limit(), mode == SearchMode.SEMANTIC ? 10 : 20);
		if (mode == SearchMode.KEYWORD) {
			if (options != null && options.semanticTags() != null && !options.semanticTags().isEmpty()) {
				throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
			}
			CursorResponse<ContentListItemResponse> response = contentService.getContents(
				new ContentSearchCondition(type, queryText, options == null ? null : options.tagsIn()),
				options == null ? null : options.cursor(),
				options == null ? null : options.idAfter(),
				limit,
				options == null || options.sortBy() == null ? "createdAt" : options.sortBy(),
				direction(options == null ? null : options.sortDirection())
			);
			saveCandidateIds(response.data().stream().map(ContentListItemResponse::id).toList(), toolContext);
			return new ContentSearchResult(mode, List.of(), response);
		}
		if (queryText == null || queryText.isBlank()) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		if (options != null && (options.cursor() != null || options.idAfter() != null
			|| options.sortBy() != null || options.sortDirection() != null
			|| (options.tagsIn() != null && !options.tagsIn().isEmpty()))) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		List<ContentCandidate> candidates = semanticCandidateSearchService.search(
			queryText,
			new SemanticCandidateCondition(type, options == null ? null : options.semanticTags()),
			limit
		);
		saveCandidateIds(candidates.stream().map(ContentCandidate::contentId).toList(), toolContext);
		return new ContentSearchResult(mode, candidates, null);
	}

	// 기존 Java 호출은 기본 시맨틱 검색과 후보 저장 동작을 유지한다.
	public List<ContentCandidate> searchContents(String queryText, ContentType type, ToolContext toolContext) {
		return searchContents(queryText, type, null, toolContext).semanticResults();
	}

	@Tool(description = """
	콘텐츠 UUID로 제목, 설명, 타입, 실제 tag/value, MOPL 평균 평점과 리뷰 수를 조회합니다.
	externalRating과 averageRating은 다른 통계입니다. 리뷰 원문은 getContentReviews로 조회하세요.
	""")
	public ContentDetails getContent(UUID contentId) {
		ContentResponse content = contentService.getContent(contentId);
		List<ContentTagDto> tags = contentTagRepository.findAllByContentId(contentId).stream()
			.map(tag -> new ContentTagDto(tag.getTag(), tag.getValue()))
			.toList();
		Double averageRating = reviewRepository.findAverageRatingByContentId(contentId);
		return new ContentDetails(content, tags, averageRating == null ? 0.0 : averageRating,
			reviewRepository.countByContentId(contentId));
	}

	@Tool(description = """
	콘텐츠 UUID의 실제 리뷰 원문, 개별 rating, 작성자와 작성 시각을 페이지로 조회합니다.
	page는 0부터 시작합니다. 기본 size는 20, 최대 100입니다.
	정렬은 createdAt, updatedAt, rating과 ASCENDING/DESCENDING입니다.
	리뷰 분위기를 설명할 때 실제 조회한 원문만 근거로 삼고 조회 범위/전체 리뷰 수를 구분하세요.
	""")
	public ReviewListResponse getContentReviews(
		UUID contentId,
		@ToolParam(required = false) Integer page,
		@ToolParam(required = false) Integer size,
		@ToolParam(required = false) String sortBy,
		@ToolParam(required = false) String sortDirection
	) {
		int pageNumber = page == null ? 0 : page;
		String field = sortBy == null ? "createdAt" : sortBy;
		if (pageNumber < 0 || !Set.of("createdAt", "updatedAt", "rating").contains(field)) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
		Sort.Direction order = "ASCENDING".equals(direction(sortDirection)) ? Sort.Direction.ASC : Sort.Direction.DESC;
		return reviewService.getReviews(contentId, PageRequest.of(pageNumber, pageLimit(size, 20),
			Sort.by(order, field).and(Sort.by(order, "id"))));
	}

	@Tool(description = """
	플레이리스트 목록과 각 목록의 콘텐츠, 소유자, 구독자 수, 내 구독 여부를 조회합니다.
	ownedByMe=true는 내가 만든 목록, ownerId는 특정 사용자의 UUID,
	subscribedByMe=true는 내가 구독한 목록입니다. owner/subscriber 조건은 함께 적용됩니다.
	ownedByMe와 다른 ownerId를 동시에 지정하지 마세요. 이름만 알고 UUID를 모르면 사용자에게 확인하세요.
	keywordLike는 제목/설명 검색입니다. 정렬은 updatedAt 또는 subscribeCount와 ASCENDING/DESCENDING입니다.
	다음 페이지는 nextCursor와 nextIdAfter를 cursor/idAfter에 함께 전달하세요.
	여러 플레이리스트를 비교할 때 실제 조회한 콘텐츠 UUID, 태그, 평점과 구독자 수를 근거로 답하세요.
	""")
	public CursorResponse<PlaylistResponse> getPlaylists(
		@ToolParam(required = false) PlaylistQuery query,
		ToolContext toolContext
	) {
		UUID currentUserId = (UUID) toolContext.getContext().get("currentUserId");
		UUID ownerId = query == null ? null : query.ownerId();
		if (query != null && Boolean.TRUE.equals(query.ownedByMe())) {
			if (ownerId != null && !ownerId.equals(currentUserId)) {
				throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
			}
			ownerId = currentUserId;
		}
		return playlistService.getPlaylists(
			query == null ? null : query.cursor(), query == null ? null : query.idAfter(),
			pageLimit(query == null ? null : query.limit(), 20),
			query == null || query.sortBy() == null ? "updatedAt" : query.sortBy(),
			direction(query == null ? null : query.sortDirection()), currentUserId,
			query != null && Boolean.TRUE.equals(query.subscribedByMe()) ? currentUserId : null,
			ownerId, query == null ? null : query.keywordLike()
		);
	}

	@Tool(description = "플레이리스트 UUID로 소유자, 콘텐츠 전체 목록, 구독자 수와 내 구독 여부를 조회합니다. 구독자 개별 명단은 제공하지 않습니다.")
	public PlaylistResponse getPlaylist(UUID playlistId, ToolContext toolContext) {
		return playlistService.getPlaylist(playlistId, (UUID) toolContext.getContext().get("currentUserId"));
	}

	@Tool(description = """
	현재 시청 정보를 조회합니다. contentId를 지정하면 해당 콘텐츠의 현재 시청자 목록과 totalCount를 반환합니다.
	watcherId를 지정하면 해당 사용자의 현재 시청 세션을 반환하며, 두 ID는 동시에 지정하지 마세요.
	두 ID가 없으면 현재 사용자의 세션을 조회합니다. 세션이 없으면 session은 null입니다.
	contentId 조회의 options는 watcherNameLike, cursor/idAfter, limit, sortDirection입니다.
	정렬 기준은 기존 기능의 createdAt이며 다음 페이지는 두 커서를 함께 전달하세요.
	이 정보는 현재 시청 세션이며 과거 시청 이력과 다릅니다.
	""")
	public WatchingResult getWatchingSessions(
		@ToolParam(required = false) UUID contentId,
		@ToolParam(required = false) UUID watcherId,
		@ToolParam(required = false) WatchingQuery options,
		ToolContext toolContext
	) {
		if (contentId != null) {
			if (watcherId != null) throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
			return new WatchingResult(null, watchingSessionService.getWatchingSessions(
				contentId, options == null ? null : options.watcherNameLike(),
				options == null ? null : options.cursor(), options == null ? null : options.idAfter(),
				pageLimit(options == null ? null : options.limit(), 20), "createdAt",
				direction(options == null ? null : options.sortDirection())
			));
		}
		if (options != null) throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		UUID userId = watcherId == null ? (UUID) toolContext.getContext().get("currentUserId") : watcherId;
		return new WatchingResult(watchingSessionService.getWatchingSession(userId), null);
	}

	@Tool(description = """
	현재 사용자의 과거 시청 이력을 조회합니다. 현재 시청 여부는 getWatchingSessions를 사용하세요.
	contentId가 없으면 마지막 시청 시각순으로 최근 콘텐츠를 반환합니다. limit 기본 20, 최대 100입니다.
	삭제된 콘텐츠는 최근 목록에서 제외됩니다. 전체 이력이나 개별 시청 이벤트 목록은 아닙니다.
	타입 필터와 페이지 조회는 지원하지 않습니다. 영화 요청은 반환된 최근 목록의 type을 기준으로 고르세요.
	contentId가 있으면 최근 목록의 limit과 무관하게 전체 저장 이력에서 해당 콘텐츠 기록의 존재 여부를 확인합니다.
	viewed는 저장된 기록의 존재 여부이며 실제 감상 완료를 의미하지 않습니다.
	firstViewedAt/lastViewedAt/viewCount는 저장된 값만 반환하며 개별 시청 시각이나 시간을 추측하지 마세요.
	이미 본 콘텐츠 중 다시 추천할 때 이 목록의 실제 콘텐츠만 사용하세요. 목록의 contentId는 생성 후보로 등록됩니다.
	""")
	public ViewingHistoryResult getViewingHistory(
		@ToolParam(required = false) UUID contentId,
		@ToolParam(required = false) Integer limit,
		ToolContext toolContext
	) {
		UUID userId = (UUID) toolContext.getContext().get("currentUserId");
		int size = pageLimit(limit, 20);
		if (contentId != null) {
			boolean viewed = contentViewRepository.existsById(new ContentViewId(userId, contentId));
			if (viewed) {
				saveCandidateIds(List.of(contentId), toolContext);
			}
			return new ViewingHistoryResult(contentId, viewed, List.of(), null);
		}
		List<ViewingHistoryItem> items = contentViewRepository.findRecentByUserId(userId, PageRequest.of(0, size))
			.stream().map(view -> new ViewingHistoryItem(view.getContent().getId(),
				view.getContent().getTitle(), view.getContent().getDescription(), view.getContent().getType(),
				view.getFirstViewedAt(), view.getLastViewedAt(), view.getViewCount())).toList();
		saveCandidateIds(items.stream().map(ViewingHistoryItem::contentId).toList(), toolContext);
		return new ViewingHistoryResult(null, null, items, size);
	}

	public record ViewingHistoryItem(UUID contentId, String title, String description, ContentType type,
		LocalDateTime firstViewedAt, LocalDateTime lastViewedAt, Long viewCount) {}

	public record ViewingHistoryResult(UUID contentId, Boolean viewed, List<ViewingHistoryItem> recentViews,
		Integer limit) {}

	private void saveCandidateIds(List<UUID> contentIds, ToolContext toolContext) {
		aiPlaylistCandidateStore.saveCandidates(
			(UUID) toolContext.getContext().get("currentUserId"),
			(String) toolContext.getContext().get("sessionId"), Set.copyOf(contentIds));
	}

	private int pageLimit(Integer limit, int defaultLimit) {
		if (limit != null && limit <= 0) throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		return limit == null ? defaultLimit : Math.min(limit, 100);
	}

	private String direction(String value) {
		return ContentSortDirection.from(value == null ? "DESCENDING" : value).name();
	}

	public enum SearchMode { SEMANTIC, KEYWORD }

	public record ContentSearchOptions(
		@ToolParam(required = false) SearchMode mode,
		@ToolParam(required = false) List<String> tagsIn,
		@ToolParam(required = false) List<ContentTagCondition> semanticTags,
		@ToolParam(required = false) Integer limit,
		@ToolParam(required = false) String sortBy,
		@ToolParam(required = false) String sortDirection,
		@ToolParam(required = false) String cursor,
		@ToolParam(required = false) UUID idAfter
	) {}

	public record ContentSearchResult(SearchMode mode, List<ContentCandidate> semanticResults,
		CursorResponse<ContentListItemResponse> keywordResults) {}

	public record ContentDetails(ContentResponse content, List<ContentTagDto> tags,
		double averageRating, long reviewCount) {}

	public record PlaylistQuery(
		@ToolParam(required = false) UUID ownerId,
		@ToolParam(required = false) Boolean ownedByMe,
		@ToolParam(required = false) Boolean subscribedByMe,
		@ToolParam(required = false) String keywordLike,
		@ToolParam(required = false) Integer limit,
		@ToolParam(required = false) String sortBy,
		@ToolParam(required = false) String sortDirection,
		@ToolParam(required = false) String cursor,
		@ToolParam(required = false) UUID idAfter
	) {}

	public record WatchingQuery(
		@ToolParam(required = false) String watcherNameLike,
		@ToolParam(required = false) Integer limit,
		@ToolParam(required = false) String sortDirection,
		@ToolParam(required = false) String cursor,
		@ToolParam(required = false) UUID idAfter
	) {}

	public record WatchingResult(WatchingSessionResponse session, CursorResponse<WatchingSessionResponse> sessions) {}

	@Tool(description = """
        사용자가 자신의 취향, 과거 시청 이력, 평가 기록을 기반으로
        콘텐츠를 추천해달라고 요청할 때 사용합니다.
        일반적인 조건이나 분위기를 기준으로 콘텐츠를 찾는 경우에는
        searchContents를 사용하세요.
        """)
	public List<RecommendationItem> recommendContentsForUser(
		ToolContext toolContext
	) {
		UUID currentUserId =
			(UUID) toolContext.getContext().get("currentUserId");

		String sessionId =
			(String) toolContext.getContext().get("sessionId");

		List<RecommendationItem> recommendations =
			recommendationService.getRecommendations(currentUserId);

		aiPlaylistCandidateStore.saveCandidates(
			currentUserId,
			sessionId,
			recommendations.stream()
				.map(RecommendationItem::contentId)
				.collect(java.util.stream.Collectors.toSet())
		);

		return recommendations;
	}

	@Tool(description = """
	사용자가 현재 대화에서 구성한 콘텐츠들로
	플레이리스트를 실제로 생성해 달라고 명확하게 요청했을 때 사용합니다.
	사용자의 명확한 생성 요청이 있기 전에는 호출하지 마세요.
	
	플레이리스트를 생성하기 전에 사용할 콘텐츠가
	searchContents, recommendContentsForUser 또는 getViewingHistory를 통해
	실제 MOPL 콘텐츠인지 확인되어 있어야 합니다.
	현재 후보가 확인되지 않은 경우에는
	사용자의 요청에 맞는 도구를 다시 호출하여 콘텐츠를 확인한 후 이 도구를 호출하세요.
	
	contentIds에는 콘텐츠 제목이 아니라
	검색 또는 추천 결과의 contentId UUID 값을 반드시 전달하세요.
	예: "E2E Content 2"가 아니라 "22222222-2222-2222-2222-222222222222"
	""")
	public PlaylistResponse createPlaylist(
		String title,
		String description,
		List<UUID> contentIds,
		ToolContext toolContext
	) {
		if (contentIds == null || contentIds.isEmpty()) {
			throw new MoplException(
				CommonErrorCode.INVALID_INPUT_VALUE,
				"플레이리스트에는 하나 이상의 콘텐츠가 필요합니다."
			);
		}

		UUID currentUserId =
			(UUID) toolContext.getContext().get("currentUserId");

		String sessionId =
			(String) toolContext.getContext().get("sessionId");

		if (!aiPlaylistCandidateStore.containsAll(
			currentUserId,
			sessionId,
			Set.copyOf(contentIds)
		)) {
			throw new PlaylistAiContentNotSearchedException();
		}

		return playlistService.createPlaylistWithContents(
			currentUserId,
			new PlaylistCreateRequest(title, description),
			contentIds
		);
	}
}