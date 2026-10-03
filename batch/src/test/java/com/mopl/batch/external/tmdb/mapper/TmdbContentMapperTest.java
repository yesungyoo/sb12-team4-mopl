package com.mopl.batch.external.tmdb.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import com.mopl.batch.external.tmdb.dto.TmdbMovie;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TmdbContentMapperTest {

    private final TmdbContentMapper mapper =
            new TmdbContentMapper();

    @Test
    void 영화_응답을_내부_콘텐츠로_변환한다() {
        TmdbMovie movie = createMovie(
                List.of(28, 12)
        );

        ExternalContentDto result =
                mapper.fromMovie(movie);

        assertThat(result.type())
                .isEqualTo("MOVIE");

        assertThat(result.title())
                .isEqualTo("테스트 영화");

        assertThat(result.externalSource())
                .isEqualTo("TMDB");

        assertThat(result.externalId())
                .isEqualTo("123");

        assertThat(result.thumbnailUrl())
                .isEqualTo(
                        "https://image.tmdb.org/t/p/w500/poster.jpg"
                );

        assertThat(result.tags())
                .containsExactly(
                        new ExternalContentTagDto(
                                "GENRE",
                                "ACTION"
                        ),
                        new ExternalContentTagDto(
                                "GENRE",
                                "ADVENTURE"
                        )
                );
    }

    @Test
    void 복합_장르를_여러_태그로_분해한다() {
        TmdbMovie movie =
                createMovie(
                        List.of(10759)
                );

        ExternalContentDto result =
                mapper.fromMovie(movie);

        assertThat(result.tags())
                .containsExactly(
                        new ExternalContentTagDto(
                                "GENRE",
                                "ACTION"
                        ),
                        new ExternalContentTagDto(
                                "GENRE",
                                "ADVENTURE"
                        )
                );
    }

    @Test
    void 복합_장르에서_중복_태그를_제거한다() {
        TmdbMovie movie =
                createMovie(
                        List.of(
                                28,
                                10759
                        )
                );

        ExternalContentDto result =
                mapper.fromMovie(movie);

        assertThat(result.tags())
                .containsExactly(
                        new ExternalContentTagDto(
                                "GENRE",
                                "ACTION"
                        ),
                        new ExternalContentTagDto(
                                "GENRE",
                                "ADVENTURE"
                        )
                );
    }

    @Test
    void SF_장르는_추천에서_사용하는_값으로_매핑한다() {
        TmdbMovie movie =
                createMovie(
                        List.of(878)
                );

        ExternalContentDto result =
                mapper.fromMovie(movie);

        assertThat(result.tags())
                .containsExactly(
                        new ExternalContentTagDto(
                                "GENRE",
                                "SF"
                        )
                );
    }

    private TmdbMovie createMovie(
            List<Integer> genreIds
    ) {
        return new TmdbMovie(
                123L,
                "테스트 영화",
                "테스트 설명",
                LocalDate.of(
                        2026,
                        9,
                        1
                ),
                "/poster.jpg",
                "/backdrop.jpg",
                genreIds,
                100.5,
                8.2,
                1000L
        );
    }
}
