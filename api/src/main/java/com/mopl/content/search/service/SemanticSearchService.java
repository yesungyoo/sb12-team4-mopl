package com.mopl.content.search.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.content.SemanticSearchUnavailableException;
import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.config.AiAvailability;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.exception.AiClientException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SemanticSearchService {

    private static final String EMBEDDING_FIELD = "embedding";
    private static final int NUM_CANDIDATES_MULTIPLIER = 5;
    private static final int MAX_NUM_CANDIDATES = 10_000;

    private final EmbeddingClient embeddingClient;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentRepository contentRepository;
    private final AiAvailability aiAvailability;

    public ContentListResponse search(String query, Pageable pageable) {
        if (!aiAvailability.isAvailable()) {
            log.debug(
                    "AI를 사용할 수 없어 시맨틱 검색 빈 결과를 반환합니다. reason={}",
                    aiAvailability.unavailableReason()
            );

            return emptyResponse(pageable, 0L);
        }

        List<Float> queryVector;

        try {
            queryVector = createQueryVector(query);
        } catch (AiClientException exception) {
            log.warn(
                    "OpenAI Embedding API 실패로 시맨틱 검색을 수행할 수 없습니다. "
                            + "reason=EMBEDDING_API_FAILED",
                    exception
            );
            throw new SemanticSearchUnavailableException();
        }

        NativeQuery nativeQuery = buildKnnQuery(queryVector, pageable);

        SearchHits<ContentSearchDocument> searchHits =
                elasticsearchOperations.search(nativeQuery, ContentSearchDocument.class);

        List<UUID> contentIds = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(ContentSearchDocument::getId)
                .map(UUID::fromString)
                .toList();

        if (contentIds.isEmpty()) {
            return emptyResponse(pageable, searchHits.getTotalHits());
        }

        List<Content> contents = contentRepository.findAllByIdInAndDeletedAtIsNull(contentIds);

        Map<UUID, Content> contentById = contents.stream()
                .collect(Collectors.toMap(Content::getId, content -> content));

        List<ContentResponse> responses = contentIds.stream()
                .map(contentById::get)
                .filter(Objects::nonNull)
                .map(ContentResponse::from)
                .toList();

        Page<ContentResponse> contentPage =
                new PageImpl<>(responses, pageable, searchHits.getTotalHits());

        return ContentListResponse.from(contentPage);
    }

    private ContentListResponse emptyResponse(
            Pageable pageable,
            long totalHits
    ) {
        Page<ContentResponse> emptyPage =
                new PageImpl<>(List.of(), pageable, totalHits);

        return ContentListResponse.from(emptyPage);
    }

    private List<Float> createQueryVector(String query) {
        return embeddingClient.embed(new EmbeddingRequest(query))
                .embedding()
                .stream()
                .map(Double::floatValue)
                .toList();
    }

    private NativeQuery buildKnnQuery(List<Float> queryVector, Pageable pageable) {
        int neighborCount = calculateNeighborCount(pageable);
        int numCandidates = calculateNumCandidates(neighborCount);

        return NativeQuery.builder()
                .withKnnSearches(knn -> knn
                        .field(EMBEDDING_FIELD)
                        .queryVector(queryVector)
                        .k(neighborCount)
                        .numCandidates(numCandidates))
                .withPageable(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .withTrackTotalHits(true)
                .build();
    }

    // 페이지 offset까지 포함한 결과를 먼저 뽑아야 다음 페이지를 조회할 수 있으므로 k 계산
    private int calculateNeighborCount(Pageable pageable) {
        long neighborCount = pageable.getOffset() + pageable.getPageSize();

        if (neighborCount > MAX_NUM_CANDIDATES) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        return Math.toIntExact(neighborCount);
    }

    // k 보다 넓은 후보군에서 근접 벡터를 탐색하도록 numCandidates 계산
    private int calculateNumCandidates(int neighborCount) {
        long numCandidates = (long) neighborCount * NUM_CANDIDATES_MULTIPLIER;
        return (int) Math.min(numCandidates, MAX_NUM_CANDIDATES);
    }
}
