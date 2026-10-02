package com.mopl.content.search.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.content.dto.*;
import com.mopl.core.common.dto.CursorResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.domain.content.entity.Content;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ContentSearchService {

    private static final int MAX_LIMIT = 100;
    private static final String TAGS_PATH = "tags";
    private static final String TAG_VALUE_FIELD = "tags.value";

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;

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

        ContentSortDirection sortDirection = ContentSortDirection.from(sortDirectionParam);

        NativeQuery query = buildQuery(
                condition,
                cursor,
                idAfter,
                safeLimit + 1,
                sortBy,
                sortDirection
        );

        SearchHits<ContentSearchDocument> searchHits =
                elasticsearchOperations.search(
                        query,
                        ContentSearchDocument.class
                );

        List<SearchHit<ContentSearchDocument>> hits = searchHits.getSearchHits();

        boolean hasNext = hits.size() > safeLimit;

        List<SearchHit<ContentSearchDocument>> pageHits = hasNext
                ? hits.subList(0, safeLimit)
                : hits;

        List<UUID> contentIds = pageHits.stream()
                .map(SearchHit::getContent)
                .map(ContentSearchDocument::getId)
                .map(UUID::fromString)
                .toList();

        Map<UUID, Content> contentById = contentRepository
                .findAllByIdInAndDeletedAtIsNull(contentIds)
                .stream()
                .collect(Collectors.toMap(
                        Content::getId,
                        content -> content
                ));

        List<ContentListItemResponse> data =
                pageHits.stream()
                        .map(hit ->
                                toResponse(
                                        hit,
                                        contentById
                                )
                        )
                        .filter(Objects::nonNull)
                        .toList();

        String nextCursor = null;
        String nextIdAfter = null;

        if (hasNext && !pageHits.isEmpty()) {
            SearchHit<ContentSearchDocument> lastHit =
                    pageHits.get(pageHits.size() - 1);

            List<Object> sortValues = lastHit.getSortValues();

            if (sortValues.size() >= 2) {
                nextCursor = String.valueOf(
                                sortValues.get(0)
                        );

                nextIdAfter = lastHit.getContent().getId();
            }
        }

        return CursorResponse.of(
                data,
                nextCursor,
                nextIdAfter,
                hasNext,
                searchHits.getTotalHits(),
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
			.withPageable(PageRequest.of(0, contentIds.size()))
			.build();

		SearchHits<ContentSearchDocument> searchHits =
			elasticsearchOperations.search(
				query,
				ContentSearchDocument.class
			);

		return searchHits.getSearchHits().stream()
			.map(SearchHit::getContent)
			.collect(Collectors.toMap(
				document -> UUID.fromString(document.getId()),
				document -> document
			));
	}

    private ContentListItemResponse toResponse(
            SearchHit<ContentSearchDocument> hit,
            Map<UUID, Content> contentById
    ) {
        ContentSearchDocument document = hit.getContent();

        UUID contentId = UUID.fromString(document.getId());

        Content content = contentById.get(contentId);

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
            String cursor,
            UUID idAfter,
            int limitPlusOne,
            ContentSortBy sortBy,
            ContentSortDirection sortDirection
    ) {
        NativeQueryBuilder queryBuilder = NativeQuery.builder()
                .withQuery(buildSearchQuery(condition.keywordLike()))
                .withPageable(PageRequest.of(
                        0,
                        limitPlusOne,
                        buildSort(
                                sortBy,
                                sortDirection
                        )
                ))
                .withTrackTotalHits(true);

        List<Query> filters = buildFilters(condition);

        if (!filters.isEmpty()) {
            queryBuilder.withFilter(
                    Query.of(q -> q.bool(b -> b.filter(filters)))
            );
        }

        if (cursor != null && idAfter != null) {
            queryBuilder.withSearchAfter(
                    buildSearchAfter(
                            cursor,
                            idAfter,
                            sortBy
                    )
            );
        }

        return queryBuilder.build();
    }

    private Query buildSearchQuery(String keywordLike) {
        if (!StringUtils.hasText(keywordLike)) {
            return Query.of(q -> q.matchAll(m -> m));
        }

        return Query.of(q -> q.multiMatch(m -> m
                .query(keywordLike)
                .fields(
                        "title^2",
                        "description"
                )
        ));
    }

    private List<Query> buildFilters(ContentSearchCondition condition) {
        List<Query> filters = new ArrayList<>();

        if (condition.typeEqual() != null) {
            filters.add(
                    Query.of(q -> q.term(t -> t
                            .field("type")
                            .value(condition.typeEqual().name())
                    ))
            );
        }

        List<Query> tagValueQueries = condition.tagsIn().stream()
                .filter(StringUtils::hasText)
                .map(value -> Query.of(query ->
                        query.term(term -> term.field(TAG_VALUE_FIELD)
                                .value(value)))
                )
                .toList();

        if (!tagValueQueries.isEmpty()) {
            Query tagValuesQuery = Query.of(query -> query.bool(bool ->
                    bool.should(tagValueQueries)
                            .minimumShouldMatch("1")));

            filters.add(Query.of(query -> query.nested(nested ->
                    nested.path(TAGS_PATH)
                            .query(tagValuesQuery))));
        }
        return filters;
    }

    private Sort buildSort(
            ContentSortBy sortBy,
            ContentSortDirection sortDirection
    ) {
        Sort.Direction direction = sortDirection
                == ContentSortDirection.ASCENDING
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        return Sort.by(new Sort.Order(
                direction,
                sortBy.getElasticsearchField()
        ),
                Sort.Order.asc("id"));
    }

    private List<Object> buildSearchAfter(
            String cursor,
            UUID idAfter,
            ContentSortBy sortBy
    ) {
        Object cursorValue = parseCursor(cursor, sortBy);

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
                // Elasticsearch data sort value는 epoch millis 기반
                case CREATED_AT -> Long.parseLong(cursor);
                case WATCHER_COUNT -> Long.parseLong(cursor);
                case RATE -> Double.parseDouble(cursor);
            };
        } catch (NumberFormatException e) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateRequest(
            String cursor,
            UUID idAfter,
            int limit
    ) {
        if (limit <= 0) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        // cursor와 idAfter는 둘 다 있거나 둘 다 없어야 함
        if ((cursor == null) != (idAfter == null)) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }
}