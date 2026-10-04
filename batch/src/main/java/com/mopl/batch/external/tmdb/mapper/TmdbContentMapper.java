package com.mopl.batch.external.tmdb.mapper;

import static java.util.Map.entry;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import com.mopl.batch.external.tmdb.dto.TmdbMovie;
import com.mopl.batch.external.tmdb.dto.TmdbTv;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TmdbContentMapper {

    private static final String TMDB_IMAGE_BASE_URL =
            "https://image.tmdb.org/t/p/w500";

    private static final String GENRE_TAG = "GENRE";

    private static final Map<Integer, List<String>> GENRE_VALUES =
            Map.ofEntries(
                    entry(28, List.of("ACTION")),
                    entry(12, List.of("ADVENTURE")),
                    entry(16, List.of("ANIMATION")),
                    entry(35, List.of("COMEDY")),
                    entry(80, List.of("CRIME")),
                    entry(99, List.of("DOCUMENTARY")),
                    entry(18, List.of("DRAMA")),
                    entry(10751, List.of("FAMILY")),
                    entry(14, List.of("FANTASY")),
                    entry(36, List.of("HISTORY")),
                    entry(27, List.of("HORROR")),
                    entry(10402, List.of("MUSIC")),
                    entry(9648, List.of("MYSTERY")),
                    entry(10749, List.of("ROMANCE")),
                    entry(878, List.of("SF")),
                    entry(10770, List.of("TV_MOVIE")),
                    entry(53, List.of("THRILLER")),
                    entry(10752, List.of("WAR")),
                    entry(37, List.of("WESTERN")),
                    entry(10759, List.of("ACTION", "ADVENTURE")),
                    entry(10762, List.of("KIDS")),
                    entry(10763, List.of("NEWS")),
                    entry(10764, List.of("REALITY")),
                    entry(10765, List.of("SF", "FANTASY")),
                    entry(10766, List.of("SOAP")),
                    entry(10767, List.of("TALK")),
                    entry(10768, List.of("WAR", "POLITICS"))
            );

    public ExternalContentDto fromMovie(TmdbMovie movie) {
        return new ExternalContentDto(
                "MOVIE",
                movie.title(),
                movie.overview(),
                createThumbnailUrl(movie.posterPath()),
                "TMDB",
                String.valueOf(movie.id()),
                movie.releaseDate(),
                movie.popularity(),
                movie.voteAverage(),
                movie.voteCount(),
                createGenreTags(movie.genreIds())
        );
    }

    public ExternalContentDto fromTv(TmdbTv tv) {
        return new ExternalContentDto(
                "TV_SERIES",
                tv.name(),
                tv.overview(),
                createThumbnailUrl(tv.posterPath()),
                "TMDB",
                String.valueOf(tv.id()),
                tv.firstAirDate(),
                tv.popularity(),
                tv.voteAverage(),
                tv.voteCount(),
                createGenreTags(tv.genreIds())
        );
    }

    public List<ExternalContentTagDto> createGenreTags(
            List<Integer> genreIds
    ) {
        if (genreIds == null || genreIds.isEmpty()) {
            return List.of();
        }

        return genreIds.stream()
                .flatMap(genreId ->
                        GENRE_VALUES
                                .getOrDefault(genreId, List.of())
                                .stream()
                )
                .distinct()
                .map(value ->
                        new ExternalContentTagDto(
                                GENRE_TAG,
                                value
                        )
                )
                .toList();
    }

    private String createThumbnailUrl(String posterPath) {
        if (posterPath == null || posterPath.isBlank()) {
            return null;
        }

        return TMDB_IMAGE_BASE_URL + posterPath;
    }
}
