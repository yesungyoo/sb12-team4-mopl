package com.mopl.content.repository;

import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.core.domain.content.entity.Content;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.ANY
)
@EnableJpaRepositories(basePackageClasses = ContentRepository.class)
class ContentRepositoryQuerydslTest {

    @Autowired
    private ContentRepository contentRepository;

    @BeforeEach
    void setUp() {
        Content movieA = createContent(
                ContentType.MOVIE,
                "테스트 영화 A",
                LocalDate.of(2025, 1, 10),
                "4.2"
        );

        Content movieB = createContent(
                ContentType.MOVIE,
                "테스트 영화 B",
                LocalDate.of(2026, 2, 20),
                "4.8"
        );

        Content series = createContent(
                ContentType.TV_SERIES,
                "테스트 시리즈",
                LocalDate.of(2025, 5, 1),
                "4.5"
        );

        Content sport = createContent(
                ContentType.SPORT,
                "챔피언십 경기",
                LocalDate.of(2026, 6, 1),
                "3.9"
        );

        Content deletedContent = createContent(
                ContentType.MOVIE,
                "삭제된 테스트 영화",
                LocalDate.of(2025, 3, 1),
                "5.0"
        );

        deletedContent.delete();

        contentRepository.saveAllAndFlush(
                List.of(
                        movieA,
                        movieB,
                        series,
                        sport,
                        deletedContent
                )
        );
    }

    @Test
    @DisplayName("제목 키워드가 포함된 콘텐츠를 조회한다")
    void searchByKeyword() {
        ContentSearchCondition condition = new ContentSearchCondition(
                "테스트",
                null,
                null,
                null
        );

        Page<Content> result = contentRepository.search(
                condition,
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getContent())
                .extracting(Content::getTitle)
                .containsExactlyInAnyOrder(
                        "테스트 영화 A",
                        "테스트 영화 B",
                        "테스트 시리즈"
                );
    }

    @Test
    @DisplayName("콘텐츠 타입으로 필터링한다")
    void searchByType() {
        ContentSearchCondition condition = new ContentSearchCondition(
                null,
                ContentType.MOVIE,
                null,
                null
        );

        Page<Content> result = contentRepository.search(
                condition,
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .allMatch(content -> content.getType() == ContentType.MOVIE);
    }

    @Test
    @DisplayName("출시일 범위로 콘텐츠를 조회한다")
    void searchByReleaseDateRange() {
        ContentSearchCondition condition = new ContentSearchCondition(
                null,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)
        );

        Page<Content> result = contentRepository.search(
                condition,
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .extracting(Content::getTitle)
                .containsExactlyInAnyOrder(
                        "테스트 영화 B",
                        "챔피언십 경기"
                );
    }

    @Test
    @DisplayName("여러 검색 조건을 동시에 적용한다")
    void searchByMultipleConditions() {
        ContentSearchCondition condition = new ContentSearchCondition(
                "테스트",
                ContentType.MOVIE,
                LocalDate.of(2026, 1, 1),
                null
        );

        Page<Content> result = contentRepository.search(
                condition,
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle())
                .isEqualTo("테스트 영화 B");
    }

    @Test
    @DisplayName("삭제된 콘텐츠는 검색 결과에서 제외한다")
    void excludeDeletedContent() {
        ContentSearchCondition condition = new ContentSearchCondition(
                "삭제된 테스트 영화",
                null,
                null,
                null
        );

        Page<Content> result = contentRepository.search(
                condition,
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("평점 내림차순으로 정렬한다")
    void sortByExternalRatingDescending() {
        ContentSearchCondition condition = new ContentSearchCondition(
                null,
                ContentType.MOVIE,
                null,
                null
        );

        Pageable pageable = PageRequest.of(
                0,
                20,
                Sort.by(
                        Sort.Direction.DESC,
                        "externalRating"
                )
        );

        Page<Content> result = contentRepository.search(
                condition,
                pageable
        );

        assertThat(result.getContent())
                .extracting(Content::getTitle)
                .containsExactly(
                        "테스트 영화 B",
                        "테스트 영화 A"
                );
    }

    @Test
    @DisplayName("검색 결과에 페이지네이션을 적용한다")
    void searchWithPagination() {
        ContentSearchCondition condition = new ContentSearchCondition(
                null,
                ContentType.MOVIE,
                null,
                null
        );

        Pageable pageable = PageRequest.of(
                0,
                1,
                Sort.by(
                        Sort.Direction.DESC,
                        "externalRating"
                )
        );

        Page<Content> result = contentRepository.search(
                condition,
                pageable
        );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle())
                .isEqualTo("테스트 영화 B");

        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    private Content createContent(
            ContentType type,
            String title,
            LocalDate releaseDate,
            String externalRating
    ) {
        return new Content(
                type,
                title,
                "QueryDSL 테스트 콘텐츠",
                null,
                ExternalSource.MANUAL,
                null,
                releaseDate,
                null,
                new BigDecimal(externalRating),
                null
        );
    }
}