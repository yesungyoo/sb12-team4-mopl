package com.mopl.content.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.dto.ContentSortBy;
import com.mopl.content.dto.ContentSortDirection;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.domain.content.entity.Content;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSearchService {

    private static final int MAX_LIMIT = 100;
    private static final String TAGS_PATH = "tags";
    private static final String TAG_VALUE_FIELD = "tags.value";

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;
    private final ContentSearchRepository contentSearchRepository;

    public CursorResponse<ContentListItemResponse> search(
            ContentSearchCondition condition,
            String cursor,
            UUID idAfter,
            int limit,
            String sortByParam,
            String sortDirectionParam
    ) {
        validateRequest(cursor, idAfter, limit);

        int safeLimit = Math.min(limit, MAX_LIMIT);

        ContentSortBy sortBy = ContentSortBy.from(sortByParam);
        ContentSortDirection sortDirection =
                ContentSortDirection.from(sortDirectionParam);

        List<Object> initialSearchAfter = cursor == null
                ? List.of()
                : buildSearchAfter(
                cursor,
                idAfter,
                sortBy
        );

        // [#98 변경]
        // stale ES 문서 때문에 페이지가 비지 않도록
        // 실제 활성 콘텐츠를 limit + 1개 확보할 때까지 조회한다.
        ActiveSearchResult activeSearchResult =
                collectActiveHits(
                        condition,
                        initialSearchAfter,
                        safeLimit + 1,
                        sortBy,
                        sortDirection
                );

        List<SearchHit<ContentSearchDocument>> activeHits =
                activeSearchResult.activeHits();

        boolean hasNext = activeHits.size() > safeLimit;

        List<SearchHit<ContentSearchDocument>> pageHits = hasNext
                ? activeHits.subList(0, safeLimit)
                : activeHits;

        List<ContentListItemResponse> data = pageHits.stream()
                .map(hit -> toResponse(
                        hit,
                        activeSearchResult.contentById()
                ))
                .filter(Objects::nonNull)
                .toList();

        String nextCursor = null;
        String nextIdAfter = null;

        // [#98 변경]
        // raw ES hit가 아니라 실제 반환한 마지막 활성 콘텐츠를
        // 다음 cursor의 기준으로 사용한다.
        if (hasNext && !pageHits.isEmpty()) {
            SearchHit<ContentSearchDocument> lastHit =
                    pageHits.get(pageHits.size() - 1);

            List<Object> sortValues =
                    lastHit.getSortValues();

            if (sortValues.size() >= 2) {
                nextCursor =
                        String.valueOf(sortValues.get(0));

                nextIdAfter =
                        lastHit.getContent().getId();
            }
        }

        // [#98 변경]
        // MySQL에는 없지만 ES에는 남은 문서를 발견한 경우
        // 다음 검색부터 다시 노출되지 않도록 best-effort 정리한다.
        cleanupStaleDocuments(
                activeSearchResult.staleDocumentIds()
        );

        long totalCount = Math.max(
                0L,
                activeSearchResult.totalHits()
                        - activeSearchResult
                        .staleDocumentIds()
                        .size()
        );

        return CursorResponse.of(
                data,
                nextCursor,
                nextIdAfter,
                hasNext,
                totalCount,
                sortByParam,
                sortDirectionParam
        );
    }

    public Map<UUID, ContentSearchDocument> findDocumentsByContentIds(
            List<UUID> contentIds
    ) {
        if (contentIds.isEmpty()) {
            return Map.of();
        }

        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.ids(ids -> ids
                        .values(contentIds.stream()
                                .map(UUID::toString)
                                .toList())
                ))
                .withPageable(
                        PageRequest.of(
                                0,
                                contentIds.size()
                        )
                )
                .build();

        SearchHits<ContentSearchDocument> searchHits =
                elasticsearchOperations.search(
                        query,
                        ContentSearchDocument.class
                );

        return searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toMap(
                        document ->
                                UUID.fromString(
                                        document.getId()
                                ),
                        document -> document
                ));
    }

    private ActiveSearchResult collectActiveHits(
            ContentSearchCondition condition,
            List<Object> initialSearchAfter,
            int requiredActiveSize,
            ContentSortBy sortBy,
            ContentSortDirection sortDirection
    ) {
        List<SearchHit<ContentSearchDocument>> activeHits =
                new ArrayList<>();

        Map<UUID, Content> contentById =
                new HashMap<>();

        Set<String> staleDocumentIds =
                new LinkedHashSet<>();

        List<Object> searchAfter =
                new ArrayList<>(initialSearchAfter);

        long totalHits = 0L;
        boolean totalHitsInitialized = false;

        int fetchSize = requiredActiveSize;

        while (activeHits.size() < requiredActiveSize) {
            NativeQuery query = buildQuery(
                    condition,
                    searchAfter,
                    fetchSize,
                    sortBy,
                    sortDirection
            );

            SearchHits<ContentSearchDocument> searchHits =
                    elasticsearchOperations.search(
                            query,
                            ContentSearchDocument.class
                    );

            if (!totalHitsInitialized) {
                totalHits = searchHits.getTotalHits();
                totalHitsInitialized = true;
            }

            List<SearchHit<ContentSearchDocument>> rawHits =
                    searchHits.getSearchHits();

            if (rawHits.isEmpty()) {
                break;
            }

            List<UUID> contentIds = rawHits.stream()
                    .map(SearchHit::getContent)
                    .map(ContentSearchDocument::getId)
                    .map(UUID::fromString)
                    .toList();

            Map<UUID, Content> activeContentById =
                    contentRepository
                            .findAllByIdInAndDeletedAtIsNull(
                                    contentIds
                            )
                            .stream()
                            .collect(Collectors.toMap(
                                    Content::getId,
                                    content -> content
                            ));

            for (SearchHit<ContentSearchDocument> hit :
                    rawHits) {

                String documentId =
                        hit.getContent().getId();

                UUID contentId =
                        UUID.fromString(documentId);

                Content content =
                        activeContentById.get(contentId);

                if (content == null) {
                    staleDocumentIds.add(
                            documentId
                    );

                    continue;
                }

                if (activeHits.size()
                        < requiredActiveSize) {

                    activeHits.add(hit);

                    contentById.put(
                            contentId,
                            content
                    );
                }
            }

            if (activeHits.size()
                    >= requiredActiveSize) {
                break;
            }

            if (rawHits.size() < fetchSize) {
                break;
            }

            SearchHit<ContentSearchDocument> lastRawHit =
                    rawHits.get(rawHits.size() - 1);

            List<Object> sortValues =
                    lastRawHit.getSortValues();

            if (sortValues.size() < 2) {
                break;
            }

            searchAfter =
                    new ArrayList<>(sortValues);
        }

        return new ActiveSearchResult(
                activeHits,
                contentById,
                staleDocumentIds,
                totalHits
        );
    }

    private void cleanupStaleDocuments(
            Set<String> staleDocumentIds
    ) {
        if (staleDocumentIds.isEmpty()) {
            return;
        }

        try {
            contentSearchRepository.deleteAllById(
                    staleDocumentIds
            );
        } catch (Exception exception) {
            // 검색 자체는 성공시켜야 하므로
            // stale ES 정리 실패는 로그만 남긴다.
            log.warn(
                    "Elasticsearch stale 콘텐츠 정리 실패. contentIds={}",
                    staleDocumentIds,
                    exception
            );
        }
    }

    private ContentListItemResponse toResponse(
            SearchHit<ContentSearchDocument> hit,
            Map<UUID, Content> contentById
    ) {
        ContentSearchDocument document =
                hit.getContent();

        UUID contentId =
                UUID.fromString(
                        document.getId()
                );

        Content content =
                contentById.get(contentId);

        if (content == null) {
            return null;
        }

        return ContentListItemResponse.from(
                content,
                document
        );
    }

    private NativeQuery buildQuery(
            ContentSearchCondition condition,
            List<Object> searchAfter,
            int pageSize,
            ContentSortBy sortBy,
            ContentSortDirection sortDirection
    ) {
        NativeQueryBuilder queryBuilder =
                NativeQuery.builder()
                        .withQuery(
                                buildSearchQuery(
                                        condition.keywordLike()
                                )
                        )
                        .withPageable(
                                PageRequest.of(
                                        0,
                                        pageSize,
                                        buildSort(
                                                sortBy,
                                                sortDirection
                                        )
                                )
                        )
                        .withTrackTotalHits(true);

        List<Query> filters =
                buildFilters(condition);

        if (!filters.isEmpty()) {
            queryBuilder.withFilter(
                    Query.of(q ->
                            q.bool(b ->
                                    b.filter(filters)
                            )
                    )
            );
        }

        if (searchAfter != null
                && !searchAfter.isEmpty()) {

            queryBuilder.withSearchAfter(
                    searchAfter
            );
        }

        return queryBuilder.build();
    }

    private Query buildSearchQuery(
            String keywordLike
    ) {
        if (!StringUtils.hasText(keywordLike)) {
            return Query.of(q ->
                    q.matchAll(m -> m)
            );
        }

        return Query.of(q -> q.multiMatch(m -> m
                .query(keywordLike)
                .fields(
                        "title^2",
                        "description"
                )
                .type(TextQueryType.BoolPrefix)
        ));
    }

    private List<Query> buildFilters(
            ContentSearchCondition condition
    ) {
        List<Query> filters =
                new ArrayList<>();

        if (condition.typeEqual() != null) {
            filters.add(
                    Query.of(q ->
                            q.term(t -> t
                                    .field("type")
                                    .value(
                                            condition
                                                    .typeEqual()
                                                    .name()
                                    )
                            )
                    )
            );
        }

        List<Query> tagValueQueries =
                condition.tagsIn().stream()
                        .filter(
                                StringUtils::hasText
                        )
                        .map(value ->
                                Query.of(query ->
                                        query.term(term ->
                                                term.field(
                                                                TAG_VALUE_FIELD
                                                        )
                                                        .value(
                                                                value
                                                        )
                                        )
                                )
                        )
                        .toList();

        if (!tagValueQueries.isEmpty()) {
            Query tagValuesQuery =
                    Query.of(query ->
                            query.bool(bool ->
                                    bool.should(
                                                    tagValueQueries
                                            )
                                            .minimumShouldMatch(
                                                    "1"
                                            )
                            )
                    );

            filters.add(
                    Query.of(query ->
                            query.nested(nested ->
                                    nested.path(
                                                    TAGS_PATH
                                            )
                                            .query(
                                                    tagValuesQuery
                                            )
                            )
                    )
            );
        }

        return filters;
    }

    private Sort buildSort(
            ContentSortBy sortBy,
            ContentSortDirection sortDirection
    ) {
        Sort.Direction direction =
                sortDirection
                        == ContentSortDirection.ASCENDING
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;

        return Sort.by(
                new Sort.Order(
                        direction,
                        sortBy.getElasticsearchField()
                ),
                Sort.Order.asc("id")
        );
    }

    private List<Object> buildSearchAfter(
            String cursor,
            UUID idAfter,
            ContentSortBy sortBy
    ) {
        Object cursorValue =
                parseCursor(
                        cursor,
                        sortBy
                );

        return List.of(
                cursorValue,
                idAfter.toString()
        );
    }

    private Object parseCursor(
            String cursor,
            ContentSortBy sortBy
    ) {
        try {
            return switch (sortBy) {
                case CREATED_AT ->
                        Long.parseLong(cursor);

                case WATCHER_COUNT ->
                        Long.parseLong(cursor);

                case RATE ->
                        Double.parseDouble(cursor);
            };
        } catch (NumberFormatException exception) {
            throw new MoplException(
                    CommonErrorCode.INVALID_INPUT_VALUE
            );
        }
    }

    private void validateRequest(
            String cursor,
            UUID idAfter,
            int limit
    ) {
        if (limit <= 0) {
            throw new MoplException(
                    CommonErrorCode.INVALID_INPUT_VALUE
            );
        }

        if ((cursor == null)
                != (idAfter == null)) {

            throw new MoplException(
                    CommonErrorCode.INVALID_INPUT_VALUE
            );
        }
    }

    private record ActiveSearchResult(
            List<SearchHit<ContentSearchDocument>> activeHits,
            Map<UUID, Content> contentById,
            Set<String> staleDocumentIds,
            long totalHits
    ) {
    }
}
