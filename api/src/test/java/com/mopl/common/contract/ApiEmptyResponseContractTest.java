package com.mopl.common.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.dto.ContentResponse;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import com.mopl.recommendation.dto.RecommendationSectionsResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiEmptyResponseContractTest {

    @Test
    void contentResponseUsesEmptyTagsAndZeroStatistics() {
        ContentResponse response =
                new ContentResponse(
                        UUID.randomUUID(),
                        ContentType.MOVIE,
                        "테스트 콘텐츠",
                        "테스트 설명",
                        null,
                        ExternalSource.MANUAL,
                        null,
                        LocalDate.of(
                                2026,
                                10,
                                5
                        ),
                        null,
                        null,
                        null,
                        LocalDateTime.of(
                                2026,
                                10,
                                5,
                                12,
                                0
                        ),
                        LocalDateTime.of(
                                2026,
                                10,
                                5,
                                12,
                                0
                        )
                );

        assertThat(response.tags())
                .isEmpty();

        assertThat(response.averageRating())
                .isZero();

        assertThat(response.reviewCount())
                .isZero();

        assertThat(response.watcherCount())
                .isZero();
    }

    @Test
    void cursorResponseUsesEmptyDataAndNullCursorWhenResultIsEmpty() {
        CursorResponse<String> response =
                CursorResponse.of(
                        List.of(),
                        null,
                        null,
                        false,
                        0L,
                        "createdAt",
                        "DESCENDING"
                );

        assertThat(response.data())
                .isEmpty();

        assertThat(response.nextCursor())
                .isNull();

        assertThat(response.nextIdAfter())
                .isNull();

        assertThat(response.hasNext())
                .isFalse();

        assertThat(response.totalCount())
                .isZero();
    }

    @Test
    void recommendationSectionsUseEmptyListWhenNoSectionExists() {
        RecommendationSectionsResponse response =
                RecommendationSectionsResponse.of(
                        List.of()
                );

        assertThat(response.sections())
                .isEmpty();
    }
}
