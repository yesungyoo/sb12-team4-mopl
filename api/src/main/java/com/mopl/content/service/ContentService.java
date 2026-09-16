package com.mopl.content.service;

import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.event.ContentSearchSyncEvent;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentService {

    private final ContentRepository contentRepository;
    private final ContentSearchService contentSearchService;
    private final ApplicationEventPublisher eventPublisher;

    // 단건 조회
    public ContentResponse getContent(UUID contentId) {
        Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        return ContentResponse.from(content);
    }

    // 목록 조회
    public ContentListResponse getContents(ContentSearchCondition condition, Pageable pageable) {
        return contentSearchService.search(condition, pageable);
    }

    // 콘텐츠 등록 (관리자 전용)
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

        return ContentResponse.from(savedContent);
    }

    // 콘텐츠 수정
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

        return ContentResponse.from(content);
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
}
