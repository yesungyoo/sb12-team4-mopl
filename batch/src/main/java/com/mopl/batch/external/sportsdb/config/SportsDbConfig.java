package com.mopl.batch.external.sportsdb.config;

import com.mopl.batch.external.sportsdb.exception.SportsDbApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(SportsDbProperties.class)
public class SportsDbConfig {

    @Bean
    @Qualifier("sportsDbRestClient")
    public RestClient sportDbRestClient(SportsDbProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl() + "/api/v1/json" + properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)

                // 401 / 403
                .defaultStatusHandler(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                || status.value() == HttpStatus.FORBIDDEN.value(), (request, response) -> {
                            throw new SportsDbApiException(response.getStatusCode(), "TheSportsDb API 인증에 실해팼습니다.");
                        }
                )

                // 429
                .defaultStatusHandler(status -> status.value() == HttpStatus.TOO_MANY_REQUESTS.value(),
                        (request, response) -> {
                            throw new SportsDbApiException(response.getStatusCode(), "TheSportsDB API 요청 한도를 초과했습니다.");
                        }
                )

                // 5xx
                .defaultStatusHandler(status -> status.is5xxServerError(), (request, response) -> {
                            throw new SportsDbApiException(response.getStatusCode(), "TheSportsDB API 서버 오류가 발생했습니다.");
                        }
                )

                // 기타 4xx
                .defaultStatusHandler(status -> status.is4xxClientError(), (request, response) -> {
                            throw new SportsDbApiException(response.getStatusCode(), "TheSportsDB API 요청에 실패했습니다.");
                        }
                )
                .build();

    }
}
