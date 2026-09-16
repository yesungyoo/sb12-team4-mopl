package com.mopl.content.search.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

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

import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.domain.content.entity.Content;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ContentSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;

    public ContentListResponse search(
            ContentSearchCondition condition,
            Pageable pageable
    ) {
        NativeQuery query = buildQuery(condition, pageable);

        SearchHits<ContentSearchDocument> searchHits =
                elasticsearchOperations.search(
                        query,
                        ContentSearchDocument.class
                );

        List<UUID> contentIds = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(ContentSearchDocument::getId)
                .map(UUID::fromString)
                .toList();

        if (contentIds.isEmpty()) {
            Page<ContentResponse> emptyPage = new PageImpl<>(
                    List.of(),
                    pageable,
                    searchHits.getTotalHits()
            );

            return ContentListResponse.from(emptyPage);
        }

        List<Content> contents =
                contentRepository.findAllByIdInAndDeletedAtIsNull(contentIds);

        Map<UUID, Content> contentById = contents.stream()
                .collect(
                        Collectors.toMap(
                                Content::getId,
                                content -> content
                        )
                );

        List<ContentResponse> responses = contentIds.stream()
                .map(contentById::get)
                .filter(Objects::nonNull)
                .map(ContentResponse::from)
                .toList();

        Page<ContentResponse> contentPage = new PageImpl<>(
                responses,
                pageable,
                searchHits.getTotalHits()
        );

        return ContentListResponse.from(contentPage);
    }

    private NativeQuery buildQuery(
            ContentSearchCondition condition,
            Pageable pageable
    ) {
        NativeQueryBuilder queryBuilder = NativeQuery.builder()
                .withQuery(buildSearchQuery(condition.keyword()))
                .withPageable(toElasticsearchPageable(pageable))
                .withTrackTotalHits(true);

        List<Query> filters = buildFilters(condition);

        if (!filters.isEmpty()) {
            queryBuilder.withFilter(
                    Query.of(q -> q.bool(b -> b.filter(filters)))
            );
        }

        return queryBuilder.build();
    }

    private Query buildSearchQuery(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return Query.of(q -> q.matchAll(m -> m));
        }

        return Query.of(q -> q.multiMatch(m -> m
                .query(keyword)
                .fields(
                        "title^2",
                        "description"
                )
        ));
    }

    private List<Query> buildFilters(ContentSearchCondition condition) {
        List<Query> filters = new ArrayList<>();

        if (condition.type() != null) {
            filters.add(
                    Query.of(q -> q.term(t -> t
                            .field("type")
                            .value(condition.type().name())
                    ))
            );
        }

        if (condition.releaseDateFrom() != null
                || condition.releaseDateTo() != null) {

            filters.add(
                    Query.of(q -> q.range(r -> r.date(d -> {
                        d.field("releaseDate");

                        if (condition.releaseDateFrom() != null) {
                            d.gte(
                                    condition.releaseDateFrom().toString()
                            );
                        }

                        if (condition.releaseDateTo() != null) {
                            d.lte(
                                    condition.releaseDateTo().toString()
                            );
                        }

                        return d;
                    })))
            );
        }

        return filters;
    }

    private Pageable toElasticsearchPageable(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();

        for (Sort.Order order : pageable.getSort()) {
            String property =
                    toElasticsearchSortProperty(order.getProperty());

            if (property != null) {
                orders.add(
                        new Sort.Order(
                                order.getDirection(),
                                property
                        )
                );
            }
        }

        if (orders.isEmpty()) {
            orders.add(Sort.Order.desc("createdAt"));
        }

        orders.add(Sort.Order.asc("id"));

        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(orders)
        );
    }

    private String toElasticsearchSortProperty(String property) {
        return switch (property) {
            case "createdAt" -> "createdAt";
            case "releaseDate" -> "releaseDate";
            case "title" -> "title.keyword";
            case "externalPopularity" -> "externalPopularity";
            case "externalRating" -> "externalRating";
            default -> null;
        };
    }
}