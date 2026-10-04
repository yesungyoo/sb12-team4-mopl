package com.mopl.content.service;

import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.content.search.event.ContentSearchSyncEvent;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.review.repository.ReviewRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentService {

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
    private final ContentViewRepository contentViewRepository;
    private final ReviewRepository reviewRepository;
    private final ContentSearchService contentSearchService;
    private final ApplicationEventPublisher eventPublisher;

    public ContentResponse getContent(UUID contentId) {
        Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        return toResponse(content, contentId);
    }

    public CursorResponse<ContentListItemResponse> getContents(
            ContentSearchCondition condition,
            String cursor,
            UUID idAfter,
            int limit,
            String sortBy,
            String sortDirection
    ) {
        return contentSearchService.search(
                condition,
                cursor,
                idAfter,
                limit,
                sortBy,
                sortDirection
        );
    }

    @Transactional
    public ContentResponse createContent(ContentCreateRequest request) {
        Content content = new Content(
                request.type(),
                request.title(),
                request.description(),
                request.thumbnailUrl(),
                ExternalSource.MANUAL,
                null,
                request.releaseDate(),
                null,
                null,
                null
        );

        Content savedContent = contentRepository.save(content);

        eventPublisher.publishEvent(
                new ContentSearchSyncEvent(savedContent.getId(), false)
        );

        return toResponse(savedContent, savedContent.getId());
    }

    @Transactional
    public ContentResponse updateContent(UUID contentId, ContentUpdateRequest request) {
        Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        content.update(
                request.type(),
                request.title(),
                request.description(),
                request.thumbnailUrl(),
                request.releaseDate()
        );

        eventPublisher.publishEvent(
                new ContentSearchSyncEvent(content.getId(), false)
        );

        return toResponse(content, contentId);
    }

    @Transactional
    public void deleteContent(UUID contentId) {
        Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        content.delete();

        eventPublisher.publishEvent(
                new ContentSearchSyncEvent(content.getId(), true)
        );
    }

    private ContentResponse toResponse(Content content, UUID contentId) {
        if (contentId == null) {
            return ContentResponse.from(content);
        }

        List<String> tags = contentTagRepository.findAllByContentId(contentId)
                .stream()
                .map(ContentTag::getValue)
                .distinct()
                .toList();

        Double averageRating = reviewRepository.findAverageRatingByContentId(contentId);
        long reviewCount = reviewRepository.countByContentId(contentId);
        long watcherCount = contentViewRepository.countByContent_Id(contentId);

        return ContentResponse.from(
                content,
                tags,
                averageRating,
                reviewCount,
                watcherCount
        );
    }
}
