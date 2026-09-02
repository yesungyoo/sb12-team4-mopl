package com.mopl.batch.external.tmdb.config;

import com.mopl.batch.external.tmdb.exception.TmdbApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.HashSet;

@Configuration
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbConfig {

    @Bean
    @Qualifier("tmdbRestClient")
    public RestClient tmdbRestClient(TmdbProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)

                // 401 / 403
                .defaultStatusHandler(
                        status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                || status.value() == HttpStatus.FORBIDDEN.value(), (request, response) -> {
                            throw new TmdbApiException(response.getStatusCode(), "TMDB API 인증에 실패했습니다.");
                        }
                )

                // 429
                .defaultStatusHandler(
                        status -> status.value() == HttpStatus.TOO_MANY_REQUESTS.value(),
                        (request, response) -> {
                            throw new TmdbApiException(response.getStatusCode(), "TMDB API 요청 한도를 초과했습니다.");
                        }
                )

                // 5xx
                .defaultStatusHandler(
                        status -> status.is5xxServerError(),
                        (request, response) -> {
                            throw new TmdbApiException(response.getStatusCode(), "TMDB API 서버 오류가 발생했습니다.");
                        }
                )

                // 그 외 4xx
                .defaultStatusHandler(
                        status -> status.is4xxClientError(),
                        (request, response) -> {
                            throw new TmdbApiException(response.getStatusCode(), "TMDB API 요청에 실패했습니다.");
                        }
                )
                .build();
    }
}
