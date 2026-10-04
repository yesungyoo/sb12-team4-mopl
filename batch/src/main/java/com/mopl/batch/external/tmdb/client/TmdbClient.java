package com.mopl.batch.external.tmdb.client;

import com.mopl.batch.external.tmdb.dto.TmdbContentDetails;
import com.mopl.batch.external.tmdb.dto.TmdbMovieResponse;
import com.mopl.batch.external.tmdb.dto.TmdbTvResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TmdbClient {

    private final RestClient restClient;

    public TmdbClient(@Qualifier("tmdbRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public TmdbMovieResponse getPopularMovies(int page) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/3/movie/popular")
                        .queryParam("language", "ko-KR")
                        .queryParam("page", page)
                        .build()
                )
                .retrieve()
                .body(TmdbMovieResponse.class);
    }

    public TmdbTvResponse getPopularTvShows(int page) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/3/tv/popular")
                        .queryParam("language", "ko-KR")
                        .queryParam("page", page)
                        .build()
                )
                .retrieve()
                .body(TmdbTvResponse.class);
    }

    public TmdbContentDetails getMovieDetails(String externalId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/3/movie/{id}")
                        .queryParam("language", "ko-KR")
                        .build(externalId)
                )
                .retrieve()
                .body(TmdbContentDetails.class);
    }

    public TmdbContentDetails getTvDetails(String externalId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/3/tv/{id}")
                        .queryParam("language", "ko-KR")
                        .build(externalId)
                )
                .retrieve()
                .body(TmdbContentDetails.class);
    }
}
