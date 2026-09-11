package com.mopl.content.service;

import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.content.dto.*;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ContentService {

    private final ContentRepository contentRepository;

    public ContentService(ContentRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    // 단건 조회
    public ContentResponse getContent(UUID contentId) {
        Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        return ContentResponse.from(content);
    }

    // 목록 조회
    public ContentListResponse getContents(ContentSearchCondition condition, Pageable pageable) {
        Page<ContentResponse> contentPage = contentRepository
                .search(condition, pageable)
                .map(ContentResponse::from);

        return ContentListResponse.from(contentPage);
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

        return ContentResponse.from(content);
    }

    @Transactional
    public void deleteContent(UUID contentId) {
        Content content = contentRepository.findByIdAndDeletedAtIsNull(contentId)
                .orElseThrow(ContentNotFoundException::new);

        content.delete();
    }
}
