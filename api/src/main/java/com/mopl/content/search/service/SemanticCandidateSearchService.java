package com.mopl.content.search.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.condition.ContentTagCondition;
import com.mopl.content.search.condition.SemanticCandidateCondition;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.dto.ContentCandidate;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SemanticCandidateSearchService {

    private static final String EMBEDDING_FIELD = "embedding";
    private static final int NUM_CANDIDATES_MULTIPLIER = 5;
    private static final int MAX_CANDIDATE_SIZE = 10_000;
    private static final int OVER_FETCH_MULTIPLIER = 2;

    private final EmbeddingClient embeddingClient;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;

    public List<ContentCandidate> search(
            String queryText,
            SemanticCandidateCondition condition,
            int size
    ) {
        // 내부 AI 검색 요청 값 검증
        validateSearchRequest(queryText, condition, size);

        List<Float> queryVector = createQueryVector(queryText);
        NativeQuery nativeQuery = buildKnnQuery(queryVector, condition, size);

        SearchHits<ContentSearchDocument> searchHits =
                elasticsearchOperations.search(nativeQuery, ContentSearchDocument.class);

        if (searchHits.isEmpty()) {
            return List.of();
        }

        List<UUID> contentIds = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(ContentSearchDocument::getId)
                .map(UUID::fromString)
                .toList();

        List<Content> contents =
                contentRepository.findAllByIdInAndDeletedAtIsNull(contentIds);

        Map<UUID, Content> contentById = contents.stream()
                .collect(Collectors.toMap(
                        Content::getId,
                        content -> content
                ));

        // SearchHit을 그대로 순회해 ES 검색 순서와 score를 유지
        return searchHits.getSearchHits().stream()
                .map(searchHit ->
                        createCandidate(searchHit, contentById))
                .filter(Objects::nonNull)
                .limit(size)
                .toList();
    }

    private ContentCandidate createCandidate(
            SearchHit<ContentSearchDocument> searchHit,
            Map<UUID, Content> contentById
    ) {
        ContentSearchDocument searchDocument = searchHit.getContent();

        UUID contentId = UUID.fromString(searchDocument.getId());

        Content content = contentById.get(contentId);

        if (content == null) {
            return null;
        }

        return ContentCandidate.from(
                content,
                searchDocument,
                searchHit.getScore()
        );
    }

    private List<Float> createQueryVector(String queryText) {
        return embeddingClient.embed(new EmbeddingRequest(queryText))
                .embedding()
                .stream()
                .map(Double::floatValue)
                .toList();
    }

    private NativeQuery buildKnnQuery(
            List<Float> queryVector,
            SemanticCandidateCondition condition,
            int size
    ) {
        int fetchSize = calculateFetchSize(size);
        int numCandidates = calculateNumCandidates(fetchSize);
        List<Query> filters = buildFilters(condition);

        return NativeQuery.builder()
                .withKnnSearches(knn -> {
                    knn.field(EMBEDDING_FIELD)
                            .queryVector(queryVector)
                            .k(fetchSize)
                            .numCandidates(numCandidates);

                    if (!filters.isEmpty()) {
                        knn.filter(filters);
                    }

                    return knn;
                })
                .withPageable(PageRequest.of(0, fetchSize))
                .build();
    }

    private List<Query> buildFilters(SemanticCandidateCondition condition) {
        if (condition == null) {
            return List.of();
        }

        List<Query> filters = new ArrayList<>();

        if (condition.type() != null) {
            filters.add(
                    Query.of(query -> query.term(term -> term
                            .field("type")
                            .value(condition.type().name())
                    ))
            );
        }

        condition.tags().stream()
                .map(this::buildTagFilter)
                .forEach(filters::add);

        return filters;
    }

    private Query buildTagFilter(ContentTagCondition tagCondition) {
        Query tagFilter = Query.of(query -> query.term(term -> term
                .field("tags.tag")
                .value(tagCondition.tag())
        ));

        Query valueFilter = Query.of(query -> query.term(term -> term
                .field("tags.value")
                .value(tagCondition.value())
        ));

        Query tagAndValueFilter = Query.of(query -> query.bool(bool -> bool
                .filter(List.of(tagFilter, valueFilter))
        ));

        // 같은 nested 객체 안에서 tag/value가 일치하도록 검색
        return Query.of(query -> query.nested(nested -> nested
                .path("tags")
                .query(tagAndValueFilter)
        ));
    }

    private int calculateNumCandidates(int size) {
        long numCandidates = (long) size * NUM_CANDIDATES_MULTIPLIER;

        return (int) Math.min(
                numCandidates,
                MAX_CANDIDATE_SIZE
        );
    }

    private int calculateFetchSize(int size) {
        long fetchSize = (long) size * OVER_FETCH_MULTIPLIER;

        return (int) Math.min(
                fetchSize,
                MAX_CANDIDATE_SIZE
        );
    }

    private void validateSearchRequest(
            String queryText,
            SemanticCandidateCondition condition,
            int size
    ) {
        if (!StringUtils.hasText(queryText) || size <= 0 || size > MAX_CANDIDATE_SIZE) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        if (condition == null) {
            return;
        }

        boolean hasInvalidTagCondition = condition.tags().stream()
                .anyMatch(tagCondition ->
                        tagCondition == null
                                || !StringUtils.hasText(tagCondition.tag())
                                || !StringUtils.hasText(tagCondition.value())
                );

        if (hasInvalidTagCondition) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
