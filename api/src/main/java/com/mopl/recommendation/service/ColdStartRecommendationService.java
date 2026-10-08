package com.mopl.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.recommendation.config.RecommendationProperties;
import com.mopl.recommendation.dto.RecommendationItem;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ColdStartRecommendationService {

    private static final String COLD_START_REASON = "현재 MOPL에서 인기 있는 콘텐츠입니다.";

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;
    private final RecommendationProperties recommendationProperties;

    public List<RecommendationItem> recommend() {
        return recommend(Set.of());
    }

    public List<RecommendationItem> recommend(
            Set<UUID> excludedContentIds
    ) {
        int candidateSize = recommendationProperties.candidateSize();
        int resultSize = recommendationProperties.resultSize();
        List<RecommendationItem> recommendations = new ArrayList<>();
        int page = 0;

        while (recommendations.size() < resultSize) {
            SearchHits<ContentSearchDocument> searchHits = searchPopularContents(
                    page,
                    candidateSize,
                    excludedContentIds
            );

            if (searchHits.isEmpty()) {
                break;
            }

            List<SearchHit<ContentSearchDocument>> searchHitList = searchHits.getSearchHits();
            List<UUID> contentIds = searchHitList.stream()
                    .map(SearchHit::getContent)
                    .map(ContentSearchDocument::getId)
                    .map(UUID::fromString)
                    .toList();

            Map<UUID, Content> contentById = contentRepository.findAllByIdInAndDeletedAtIsNull(contentIds)
                    .stream()
                    .collect(Collectors.toMap(Content::getId, content -> content));

            for (SearchHit<ContentSearchDocument> searchHit : searchHitList) {
                RecommendationItem recommendation = createRecommendation(searchHit, contentById);

                if (recommendation == null) {
                    continue;
                }

                recommendations.add(recommendation);

                if (recommendations.size() >= resultSize) {
                    break;
                }
            }

            if (searchHitList.size() < candidateSize) {
                break;
            }

            page++;
        }

        return List.copyOf(recommendations);
    }

    private SearchHits<ContentSearchDocument> searchPopularContents(
            int page,
            int candidateSize,
            Set<UUID> excludedContentIds
    ) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(buildPopularQuery(excludedContentIds))
                .withPageable(PageRequest.of(page, candidateSize, buildPopularitySort()))
                .build();

        return elasticsearchOperations.search(query, ContentSearchDocument.class);
    }

    private Query buildPopularQuery(Set<UUID> excludedContentIds) {
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

    private RecommendationItem createRecommendation(
            SearchHit<ContentSearchDocument> searchHit,
            Map<UUID, Content> contentById
    ) {
        ContentSearchDocument document = searchHit.getContent();
        UUID contentId = UUID.fromString(document.getId());
        Content content = contentById.get(contentId);

        // Elasticsearch에는 남아 있지만 MySQL에서 삭제된 콘텐츠는 제외
        if (content == null) {
            return null;
        }

        ContentCandidate candidate = ContentCandidate.from(content, document, 0.0);
        return RecommendationItem.from(candidate, COLD_START_REASON);
    }
}
