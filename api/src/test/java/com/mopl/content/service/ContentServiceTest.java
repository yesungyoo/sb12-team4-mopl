package com.mopl.content.service;

import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.content.dto.*;
import com.mopl.content.repository.ContentRepository;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    @Mock
    private ContentRepository contentRepository;

    private ContentService contentService;

    @BeforeEach
    void setUp() {
        contentService = new ContentService(contentRepository);
    }

    @Test
    @DisplayName("콘텐츠 ID로 삭제되지 않은 콘텐츠를 조회한다")
    void getContentSuccess() {
        UUID contentId = UUID.randomUUID();
        Content content = createContent("테스트 영화");

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));

        ContentResponse response = contentService.getContent(contentId);

        assertThat(response.title()).isEqualTo("테스트 영화");
        assertThat(response.type()).isEqualTo(ContentType.MOVIE);
        assertThat(response.externalSource()).isEqualTo(ExternalSource.MANUAL);

        verify(contentRepository).findByIdAndDeletedAtIsNull(contentId);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠를 조회하면 예외가 발생한다")
    void getContentNotFound() {
        UUID contentId = UUID.randomUUID();

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentService.getContent(contentId))
                .isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("삭제되지 않은 콘텐츠 목록을 페이지 단위로 조회한다")
    void getContentsSuccess() {
        Pageable pageable = PageRequest.of(0, 20);
        Content firstContent = createContent("테스트 영화 A");
        Content secondContent = createContent("테스트 영화 B");
        ContentSearchCondition condition = new ContentSearchCondition(
                null, null, null, null);

        Page<Content> contentPage = new PageImpl<>(
                List.of(firstContent, secondContent),
                pageable,
                2
        );

        when(contentRepository.search(condition, pageable))
                .thenReturn(contentPage);


        ContentListResponse response = contentService.getContents(condition, pageable);

        assertThat(response.contents()).hasSize(2);
        assertThat(response.contents().get(0).title()).isEqualTo("테스트 영화 A");
        assertThat(response.contents().get(1).title()).isEqualTo("테스트 영화 B");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자가 콘텐츠를 수동 등록하면 MANUAL 출처로 저장한다")
    void createContentSuccess() {
        ContentCreateRequest request = new ContentCreateRequest(
                ContentType.MOVIE,
                "관리자 등록 영화",
                "관리자 등록 테스트",
                "https://example.com/image.jpg",
                LocalDate.of(2026, 9, 8)
        );

        when(contentRepository.save(any(Content.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ContentResponse response = contentService.createContent(request);

        ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);
        verify(contentRepository).save(captor.capture());

        Content savedContent = captor.getValue();

        assertThat(savedContent.getType()).isEqualTo(ContentType.MOVIE);
        assertThat(savedContent.getTitle()).isEqualTo("관리자 등록 영화");
        assertThat(savedContent.getExternalSource()).isEqualTo(ExternalSource.MANUAL);
        assertThat(savedContent.getExternalId()).isNull();
        assertThat(savedContent.getExternalPopularity()).isNull();
        assertThat(savedContent.getExternalRating()).isNull();
        assertThat(savedContent.getExternalVoteCount()).isNull();

        assertThat(response.externalSource()).isEqualTo(ExternalSource.MANUAL);
    }

    @Test
    @DisplayName("콘텐츠의 전달된 정보만 수정한다")
    void updateContentSuccess() {
        UUID contentId = UUID.randomUUID();
        Content content = createContent("수정 전 제목");

        ContentUpdateRequest request = new ContentUpdateRequest(
                null,
                "수정 후 제목",
                "수정 후 설명",
                null,
                null
        );

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));

        ContentResponse response = contentService.updateContent(
                contentId,
                request
        );

        assertThat(response.title()).isEqualTo("수정 후 제목");
        assertThat(response.description()).isEqualTo("수정 후 설명");

        assertThat(content.getTitle()).isEqualTo("수정 후 제목");
        assertThat(content.getDescription()).isEqualTo("수정 후 설명");
        assertThat(content.getType()).isEqualTo(ContentType.MOVIE);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠를 수정하면 예외가 발생한다")
    void updateContentNotFound() {
        UUID contentId = UUID.randomUUID();

        ContentUpdateRequest request = new ContentUpdateRequest(
                null,
                "수정 제목",
                null,
                null,
                null
        );

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> contentService.updateContent(contentId, request)
        ).isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("콘텐츠를 삭제하면 삭제 시간이 기록된다")
    void deleteContentSuccess() {
        UUID contentId = UUID.randomUUID();
        Content content = createContent("삭제 테스트 영화");

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));

        contentService.deleteContent(contentId);

        assertThat(content.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠를 삭제하면 예외가 발생한다")
    void deleteContentNotFound() {
        UUID contentId = UUID.randomUUID();

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> contentService.deleteContent(contentId)
        ).isInstanceOf(ContentNotFoundException.class);
    }

    private Content createContent(String title) {
        return new Content(
                ContentType.MOVIE,
                title,
                "테스트 설명",
                null,
                ExternalSource.MANUAL,
                null,
                LocalDate.of(2026, 9, 8),
                null,
                null,
                null
        );
    }
}