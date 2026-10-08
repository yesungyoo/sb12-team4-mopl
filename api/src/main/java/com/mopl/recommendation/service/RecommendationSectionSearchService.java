package com.mopl.recommendation.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.recommendation.dto.RecommendationPreferredTag;
import com.mopl.recommendation.dto.RecommendationSectionItem;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecommendationSectionSearchService {

    private static final int MAX_SECTION_SIZE = 10;

    private static final DateTimeFormatter CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;

    public List<RecommendationSectionItem> findPopular(int size) {
        return findPopular(size, Set.of());
    }

    public List<RecommendationSectionItem> findPopular(
            int size,
            Set<UUID> excludedContentIds
    ) {
        return search(
                null,
                null,
                null,
                null,
                size,
                buildPopularitySort(),
                excludedContentIds
        );
    }

    public List<RecommendationSectionItem> findPopularByType(
            ContentType contentType,
            int size
    ) {
        return findPopularByType(contentType, size, Set.of());
    }

    public List<RecommendationSectionItem> findPopularByType(
            ContentType contentType,
            int size,
            Set<UUID> excludedContentIds
    ) {
        return search(
                contentType,
                null,
                null,
                null,
                size,
                buildPopularitySort(),
                excludedContentIds
        );
    }

    public List<RecommendationSectionItem> findPopularByPreferenceTag(
            ContentType contentType,
            RecommendationPreferredTag preferredTag,
            int size
    ) {
        return findPopularByPreferenceTag(
                contentType,
                preferredTag,
                size,
                Set.of()
        );
    }

    public List<RecommendationSectionItem> findPopularByPreferenceTag(
            ContentType contentType,
            RecommendationPreferredTag preferredTag,
            int size,
            Set<UUID> excludedContentIds
    ) {
        return search(
                contentType,
                preferredTag,
                null,
                null,
                size,
                buildPopularitySort(),
                excludedContentIds
        );
    }

    public List<RecommendationSectionItem> findNew(
            LocalDateTime createdAfter,
            int size
    ) {
        return findNew(createdAfter, size, Set.of());
    }

    public List<RecommendationSectionItem> findNew(
            LocalDateTime createdAfter,
            int size,
            Set<UUID> excludedContentIds
    ) {
        return search(
                null,
                null,
                createdAfter,
                null,
                size,
                buildNewestSort(),
                excludedContentIds
        );
    }

    public List<RecommendationSectionItem> findNewByType(
            ContentType contentType,
            LocalDateTime createdAfter,
            int size
    ) {
        return findNewByType(
                contentType,
                createdAfter,
                size,
                Set.of()
        );
    }

    public List<RecommendationSectionItem> findNewByType(
            ContentType contentType,
            LocalDateTime createdAfter,
            int size,
            Set<UUID> excludedContentIds
    ) {
        return search(
                contentType,
                null,
                createdAfter,
                null,
                size,
                buildNewestSort(),
                excludedContentIds
        );
    }

    public List<RecommendationSectionItem> findNewByPreferenceTag(
            ContentType contentType,
            RecommendationPreferredTag preferredTag,
            LocalDateTime createdAfter,
            int size
    ) {
        return findNewByPreferenceTag(
                contentType,
                preferredTag,
                createdAfter,
                size,
                Set.of()
        );
    }

    public List<RecommendationSectionItem> findNewByPreferenceTag(
            ContentType contentType,
            RecommendationPreferredTag preferredTag,
            LocalDateTime createdAfter,
            int size,
            Set<UUID> excludedContentIds
    ) {
        return search(
                contentType,
                preferredTag,
                createdAfter,
                null,
                size,
                buildPopularitySort(),
                excludedContentIds
        );
    }

    public List<RecommendationSectionItem> findByContentIds(
            List<UUID> contentIds
    ) {
        if (contentIds == null || contentIds.isEmpty()) {
            return List.of();
        }

        List<UUID> orderedContentIds = contentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_SECTION_SIZE)
                .toList();

        if (orderedContentIds.isEmpty()) {
            return List.of();
        }

        List<String> elasticsearchIds = orderedContentIds.stream()
                .map(UUID::toString)
                .toList();

        NativeQuery query = NativeQuery.builder()
                .withQuery(Query.of(q -> q.ids(ids -> ids.values(elasticsearchIds))))
                .withPageable(PageRequest.of(0, orderedContentIds.size()))
                .build();

        SearchHits<ContentSearchDocument> searchHits =
                elasticsearchOperations.search(
                        query,
                        ContentSearchDocument.class
                );

        if (searchHits.isEmpty()) {
            return List.of();
        }

        Map<UUID, ContentSearchDocument> documentById =
                searchHits.getSearchHits().stream()
                        .map(SearchHit::getContent)
                        .collect(Collectors.toMap(
                                document -> UUID.fromString(document.getId()),
                                document -> document,
                                (left, right) -> left
                        ));

        List<Content> contents =
                contentRepository.findAllByIdInAndDeletedAtIsNull(
                        orderedContentIds
                );

        Map<UUID, Content> contentById = contents.stream()
                .collect(Collectors.toMap(
                        Content::getId,
                        content -> content
                ));

        return orderedContentIds.stream()
                .map(contentId -> {
                    Content content = contentById.get(contentId);
                    ContentSearchDocument document = documentById.get(contentId);

                    if (content == null || document == null) {
                        return null;
                    }

                    return RecommendationSectionItem.from(
                            content,
                            document
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<RecommendationSectionItem> search(
            ContentType contentType,
            RecommendationPreferredTag preferredTag,
            LocalDateTime createdAfter,
            LocalDateTime createdBefore,
            int requestedSize,
            Sort sort,
            Set<UUID> excludedContentIds
    ) {
        int size = normalizeSize(requestedSize);

        Set<UUID> seenContentIds = new HashSet<>();

        if (excludedContentIds != null) {
            seenContentIds.addAll(excludedContentIds);
        }

        List<Query> filters = buildFilters(
                contentType,
                preferredTag,
                createdAfter,
                createdBefore
        );

        List<RecommendationSectionItem> results =
                new ArrayList<>(size);

        int page = 0;

        while (results.size() < size) {
            SearchHits<ContentSearchDocument> searchHits =
                    searchPage(
                            filters,
                            page,
                            size,
                            sort,
                            excludedContentIds
                    );

            List<SearchHit<ContentSearchDocument>> hitList =
                    searchHits.getSearchHits();

            if (hitList.isEmpty()) {
                break;
            }

            List<RecommendationSectionItem> pageItems =
                    toSectionItems(
                            hitList,
                            seenContentIds
                    );

            for (RecommendationSectionItem item : pageItems) {
                if (!seenContentIds.add(item.contentId())) {
                    continue;
                }

                results.add(item);

                if (results.size() >= size) {
                    break;
                }
            }

            if (hitList.size() < size) {
                break;
            }

            page++;
        }

        return List.copyOf(results);
    }

    private SearchHits<ContentSearchDocument> searchPage(
            List<Query> filters,
            int page,
            int pageSize,
            Sort sort,
            Set<UUID> excludedContentIds
    ) {
        NativeQueryBuilder queryBuilder = NativeQuery.builder()
                .withQuery(buildBaseQuery(excludedContentIds))
                .withPageable(PageRequest.of(
                        page,
                        pageSize,
                        sort
                ));

        if (!filters.isEmpty()) {
            queryBuilder.withFilter(
                    Query.of(query -> query.bool(
                            bool -> bool.filter(filters)
                    ))
            );
        }

        return elasticsearchOperations.search(
                queryBuilder.build(),
                ContentSearchDocument.class
        );
    }

    private Query buildBaseQuery(Set<UUID> excludedContentIds) {
        if (excludedContentIds == null || excludedContentIds.isEmpty()) {
            return Query.of(query -> query.matchAll(matchAll -> matchAll));
        }

        List<String> excludedIds = excludedContentIds.stream()
                .map(UUID::toString)
                .toList();

        return Query.of(query -> query.bool(bool -> bool
                .must(must -> must.matchAll(matchAll -> matchAll))
                .mustNot(mustNot -> mustNot.ids(ids -> ids.values(excludedIds)))
        ));
    }

    private List<Query> buildFilters(
            ContentType contentType,
            RecommendationPreferredTag preferredTag,
            LocalDateTime createdAfter,
            LocalDateTime createdBefore
    ) {
        List<Query> filters = new ArrayList<>();

        if (contentType != null) {
            filters.add(
                    Query.of(query -> query.term(term -> term
                            .field("type")
                            .value(contentType.name())
                    ))
            );
        }

        if (preferredTag != null) {
            filters.add(
                    buildTagFilter(
                            preferredTag.tag(),
                            preferredTag.value()
                    )
            );
        }

        if (createdAfter != null || createdBefore != null) {
            filters.add(
                    buildCreatedAtFilter(
                            createdAfter,
                            createdBefore
                    )
            );
        }

        return filters;
    }

    private Query buildTagFilter(
            String tag,
            String value
    ) {
        Query tagFilter = Query.of(query -> query.term(term -> term
                .field("tags.tag")
                .value(tag)
        ));

        Query valueFilter = Query.of(query -> query.term(term -> term
                .field("tags.value")
                .value(value)
        ));

        Query tagAndValueFilter = Query.of(query -> query.bool(
                bool -> bool.filter(List.of(
                        tagFilter,
                        valueFilter
                ))
        ));

        return Query.of(query -> query.nested(nested -> nested
                .path("tags")
                .query(tagAndValueFilter)
        ));
    }

    private Query buildCreatedAtFilter(
            LocalDateTime createdAfter,
            LocalDateTime createdBefore
    ) {
        return Query.of(query -> query.range(range -> range.date(date -> {
            date.field("createdAt");

            if (createdAfter != null) {
                date.gte(
                        createdAfter.format(CREATED_AT_FORMATTER)
                );
            }

            if (createdBefore != null) {
                date.lte(
                        createdBefore.format(CREATED_AT_FORMATTER)
                );
            }

            return date;
        })));
    }

    private List<RecommendationSectionItem> toSectionItems(
            List<SearchHit<ContentSearchDocument>> searchHits,
            Set<UUID> excludedContentIds
    ) {
        List<SearchHit<ContentSearchDocument>> eligibleHits =
                searchHits.stream()
                        .filter(searchHit -> {
                            UUID contentId = UUID.fromString(
                                    searchHit.getContent().getId()
                            );

                            return !excludedContentIds.contains(contentId);
                        })
                        .toList();

        if (eligibleHits.isEmpty()) {
            return List.of();
        }

        List<UUID> contentIds = eligibleHits.stream()
                .map(SearchHit::getContent)
                .map(ContentSearchDocument::getId)
                .map(UUID::fromString)
                .toList();

        Map<UUID, Content> contentById =
                contentRepository
                        .findAllByIdInAndDeletedAtIsNull(contentIds)
                        .stream()
                        .collect(Collectors.toMap(
                                Content::getId,
                                content -> content
                        ));

        return eligibleHits.stream()
                .map(searchHit -> toSectionItem(
                        searchHit,
                        contentById
                ))
                .filter(Objects::nonNull)
                .toList();
    }

    private RecommendationSectionItem toSectionItem(
            SearchHit<ContentSearchDocument> searchHit,
            Map<UUID, Content> contentById
    ) {
        ContentSearchDocument document =
                searchHit.getContent();

        UUID contentId =
                UUID.fromString(document.getId());

        Content content =
                contentById.get(contentId);

        if (content == null) {
            return null;
        }

        return RecommendationSectionItem.from(
                content,
                document
        );
    }

    private int normalizeSize(int requestedSize) {
        if (requestedSize <= 0) {
            return MAX_SECTION_SIZE;
        }

        return Math.min(
                requestedSize,
                MAX_SECTION_SIZE
        );
    }

    private Sort buildPopularitySort() {
        return Sort.by(
                Sort.Order.desc("watcherCount"),
                Sort.Order.desc("averageRating"),
                Sort.Order.desc("reviewCount"),
                Sort.Order.desc("externalPopularity"),
                Sort.Order.desc("externalRating"),
                Sort.Order.desc("externalVoteCount"),
                Sort.Order.desc("id")
        );
    }

    private Sort buildNewestSort() {
        return Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.asc("id")
        );
    }
}
