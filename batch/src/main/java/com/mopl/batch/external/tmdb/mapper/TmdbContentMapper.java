package com.mopl.batch.external.tmdb.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import com.mopl.batch.external.tmdb.dto.TmdbMovie;
import com.mopl.batch.external.tmdb.dto.TmdbTv;
import org.springframework.stereotype.Component;

@Component
public class TmdbContentMapper {

    private static final String TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500";

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
                movie.genreIds()
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
                tv.genreIds()
        );
    }

    private String createThumbnailUrl(String posterPath) {
        if (posterPath == null || posterPath.isBlank()) {
            return null;
        }

        return TMDB_IMAGE_BASE_URL + posterPath;
    }
}
