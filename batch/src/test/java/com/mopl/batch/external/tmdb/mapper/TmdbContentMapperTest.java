package com.mopl.batch.external.tmdb.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.tmdb.dto.TmdbMovie;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbContentMapperTest {

    private final TmdbContentMapper mapper = new TmdbContentMapper();

    @Test
    void 영화_응답을_내부_콘텐츠로_변환한다() {
        TmdbMovie movie = new TmdbMovie(
                123L,
                "테스트 영화",
                "테스트 설명",
                LocalDate.of(2026, 9, 1),
                "/poster.jpg",
                "/backdrop.jpg",
                List.of(28, 12),
                100.5,
                8.2,
                1000L
        );

        ExternalContentDto result = mapper.fromMovie(movie);

        assertThat(result.type()).isEqualTo("MOVIE");
        assertThat(result.title()).isEqualTo("테스트 영화");
        assertThat(result.externalSource()).isEqualTo("TMDB");
        assertThat(result.externalId()).isEqualTo("123");
        assertThat(result.thumbnailUrl())
                .isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg");
        assertThat(result.externalGenreIds())
                .containsExactly(28, 12);
    }
}