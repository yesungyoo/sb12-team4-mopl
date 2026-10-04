package com.mopl.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.content.search.service.ContentSearchService;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.review.repository.ReviewRepository;
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
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentTagRepository contentTagRepository;

    @Mock
    private ContentViewRepository contentViewRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ContentSearchService contentSearchService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ContentService contentService;

    @BeforeEach
    void setUp() {
        contentService = new ContentService(
                contentRepository,
                contentTagRepository,
                contentViewRepository,
                reviewRepository,
                contentSearchService,
                eventPublisher
        );
    }

    @Test
    @DisplayName("콘텐츠 ID로 삭제되지 않은 콘텐츠와 상세 통계를 조회한다")
    void getContentSuccess() {
        UUID contentId = UUID.randomUUID();
        Content content = createContent("테스트 영화");

        ContentTag firstTag = mock(ContentTag.class);
        ContentTag secondTag = mock(ContentTag.class);

        when(firstTag.getValue()).thenReturn("SF");
        when(secondTag.getValue()).thenReturn("DRAMA");

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));
        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(List.of(firstTag, secondTag));
        when(reviewRepository.findAverageRatingByContentId(contentId))
                .thenReturn(4.5);
        when(reviewRepository.countByContentId(contentId))
                .thenReturn(10L);
        when(contentViewRepository.countByContent_Id(contentId))
                .thenReturn(20L);

        ContentResponse response = contentService.getContent(contentId);

        assertThat(response.title()).isEqualTo("테스트 영화");
        assertThat(response.type()).isEqualTo(ContentType.MOVIE);
        assertThat(response.tags()).containsExactly("SF", "DRAMA");
        assertThat(response.averageRating()).isEqualTo(4.5);
        assertThat(response.reviewCount()).isEqualTo(10L);
        assertThat(response.watcherCount()).isEqualTo(20L);
        assertThat(response.externalSource()).isEqualTo(ExternalSource.MANUAL);

        verify(contentRepository).findByIdAndDeletedAtIsNull(contentId);
        verify(contentTagRepository).findAllByContentId(contentId);
        verify(reviewRepository).findAverageRatingByContentId(contentId);
        verify(reviewRepository).countByContentId(contentId);
        verify(contentViewRepository).countByContent_Id(contentId);
    }

    @Test
    @DisplayName("태그와 통계가 없는 콘텐츠는 빈 태그와 0 값을 반환한다")
    void getContentReturnsEmptyTagsAndZeroStatistics() {
        UUID contentId = UUID.randomUUID();
        Content content = createContent("통계 없는 영화");

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));
        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(List.of());
        when(reviewRepository.findAverageRatingByContentId(contentId))
                .thenReturn(null);
        when(reviewRepository.countByContentId(contentId))
                .thenReturn(0L);
        when(contentViewRepository.countByContent_Id(contentId))
                .thenReturn(0L);

        ContentResponse response = contentService.getContent(contentId);

        assertThat(response.tags()).isEmpty();
        assertThat(response.averageRating()).isZero();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.watcherCount()).isZero();
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
        String cursor = null;
        UUID idAfter = null;
        int limit = 20;
        String sortBy = "createdAt";
        String sortDirection = "DESCENDING";

        ContentSearchCondition condition = new ContentSearchCondition(
                null,
                null,
                List.of()
        );

        ContentListItemResponse firstContent = new ContentListItemResponse(
                UUID.randomUUID(),
                ContentType.MOVIE,
                "테스트 영화 A",
                "테스트 설명",
                null,
                List.of("SF"),
                4.5,
                10L,
                20L
        );

        ContentListItemResponse secondContent = new ContentListItemResponse(
                UUID.randomUUID(),
                ContentType.MOVIE,
                "테스트 영화 B",
                "테스트 설명",
                null,
                List.of("DRAMA"),
                4.0,
                5L,
                12L
        );

        CursorResponse<ContentListItemResponse> searchResponse = CursorResponse.of(
                List.of(
                        firstContent,
                        secondContent
                ),
                null,
                null,
                false,
                2L,
                sortBy,
                sortDirection
        );

        when(contentSearchService.search(
                condition,
                cursor,
                idAfter,
                limit,
                sortBy,
                sortDirection
        )).thenReturn(searchResponse);

        CursorResponse<ContentListItemResponse> response = contentService.getContents(
                condition,
                cursor,
                idAfter,
                limit,
                sortBy,
                sortDirection
        );

        assertThat(response.data()).hasSize(2);
        assertThat(response.data().get(0).title())
                .isEqualTo("테스트 영화 A");
        assertThat(response.data().get(1).title())
                .isEqualTo("테스트 영화 B");
        assertThat(response.hasNext()).isFalse();
        assertThat(response.totalCount()).isEqualTo(2L);
        assertThat(response.sortBy()).isEqualTo("createdAt");
        assertThat(response.sortDirection()).isEqualTo("DESCENDING");

        verify(contentSearchService).search(
                condition,
                cursor,
                idAfter,
                limit,
                sortBy,
                sortDirection
        );
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
        assertThat(response.tags()).isEmpty();
        assertThat(response.averageRating()).isZero();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.watcherCount()).isZero();
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
        when(contentTagRepository.findAllByContentId(contentId))
                .thenReturn(List.of());

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
