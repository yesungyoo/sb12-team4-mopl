package com.mopl.batch.external.tmdb.client;

import com.mopl.batch.external.tmdb.config.TmdbConfig;
import com.mopl.batch.external.tmdb.config.TmdbProperties;
import com.mopl.batch.external.tmdb.dto.TmdbMovieResponse;
import com.mopl.batch.external.tmdb.dto.TmdbTvResponse;
import com.mopl.batch.external.tmdb.exception.TmdbApiException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbClientIntegrationTest {

    @Test
    void 인기_영화_목록을_조회한다() {
        String accessToken = System.getenv("TMDB_ACCESS_TOKEN");

        Assumptions.assumeTrue(
                accessToken != null && !accessToken.isBlank(),
                "TMDB_ACCESS_TOKEN이 없으면 실제 API 호출 테스트를 건너뜁니다."
        );

        TmdbProperties properties = new TmdbProperties(
                "https://api.themoviedb.org",
                accessToken
        );

        RestClient restClient = new TmdbConfig()
                .tmdbRestClient(properties);

        TmdbClient tmdbClient = new TmdbClient(restClient);

        TmdbMovieResponse response = tmdbClient.getPopularMovies(1);

        assertThat(response).isNotNull();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.results()).isNotNull();
        assertThat(response.results()).isNotEmpty();

        response.results()
                .stream()
                .limit(5)
                .forEach(movie ->
                        System.out.println(
                                movie.id() + " | " + movie.title()
                        )
                );
    }

    @Test
    void 인기_TV_목록을_조회한다() {
        String accessToken = System.getenv("TMDB_ACCESS_TOKEN");

        Assumptions.assumeTrue(
                accessToken != null && !accessToken.isBlank(),
                "TMDB_ACCESS_TOKEN이 없으면 실제 API 호출 테스트를 건너뜁니다."
        );

        TmdbProperties properties = new TmdbProperties(
                "https://api.themoviedb.org",
                accessToken
        );

        RestClient restClient = new TmdbConfig()
                .tmdbRestClient(properties);

        TmdbClient tmdbClient = new TmdbClient(restClient);

        TmdbTvResponse response = tmdbClient.getPopularTvShows(1);

        assertThat(response).isNotNull();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.results()).isNotNull();
        assertThat(response.results()).isNotEmpty();

        response.results()
                .stream()
                .limit(5)
                .forEach(tv ->
                        System.out.println(
                                tv.id() + " | " + tv.name()
                        )
                );
    }

    @Test
    void 잘못된_토큰이면_TmdbApiException이_발생한다() {
        TmdbProperties properties = new TmdbProperties(
                "https://api.themoviedb.org",
                "invalid-token"
        );

        RestClient restClient = new TmdbConfig()
                .tmdbRestClient(properties);

        TmdbClient tmdbClient = new TmdbClient(restClient);

        assertThatThrownBy(() -> tmdbClient.getPopularMovies(1))
                .isInstanceOf(TmdbApiException.class)
                .hasMessage("TMDB API 인증에 실패했습니다.");
    }
}