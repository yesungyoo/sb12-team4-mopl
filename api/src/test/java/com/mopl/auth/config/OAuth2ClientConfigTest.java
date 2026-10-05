package com.mopl.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class OAuth2ClientConfigTest {

    private MockEnvironment envWithBothProviders() {
        return new MockEnvironment()
                .withProperty("GOOGLE_CLIENT_ID", "google-id")
                .withProperty("GOOGLE_CLIENT_SECRET", "google-secret")
                .withProperty("KAKAO_CLIENT_ID", "kakao-id")
                .withProperty("KAKAO_CLIENT_SECRET", "kakao-secret");
    }

    private ClientRegistrationRepository repository(MockEnvironment env) {
        return new OAuth2ClientConfig().clientRegistrationRepository(env);
    }

    @Test
    @DisplayName("redirect-base-url 이 없으면 요청 기준으로 계산하는 기본 템플릿을 쓴다")
    void defaultTemplate() {
        ClientRegistrationRepository repository = repository(envWithBothProviders());

        assertThat(repository.findByRegistrationId("google").getRedirectUri())
                .isEqualTo("{baseUrl}/{action}/oauth2/code/{registrationId}");
        assertThat(repository.findByRegistrationId("kakao").getRedirectUri())
                .isEqualTo("{baseUrl}/{action}/oauth2/code/{registrationId}");
    }

    @Test
    @DisplayName("redirect-base-url 이 있으면 구글/카카오 모두 그 주소 기준의 고정 리다이렉트 URI 를 쓴다")
    void fixedBaseUrl() {
        MockEnvironment env = envWithBothProviders()
                .withProperty("app.oauth2.redirect-base-url", "https://d2135juhoqxigt.cloudfront.net");

        ClientRegistrationRepository repository = repository(env);

        assertThat(repository.findByRegistrationId("google").getRedirectUri())
                .isEqualTo("https://d2135juhoqxigt.cloudfront.net/login/oauth2/code/{registrationId}");
        assertThat(repository.findByRegistrationId("kakao").getRedirectUri())
                .isEqualTo("https://d2135juhoqxigt.cloudfront.net/login/oauth2/code/{registrationId}");
    }

    @Test
    @DisplayName("redirect-base-url 끝의 슬래시와 공백은 제거한다")
    void trimsTrailingSlash() {
        MockEnvironment env = envWithBothProviders()
                .withProperty("app.oauth2.redirect-base-url", " https://example.com// ");

        assertThat(repository(env).findByRegistrationId("kakao").getRedirectUri())
                .isEqualTo("https://example.com/login/oauth2/code/{registrationId}");
    }

    @Test
    @DisplayName("환경변수가 설정된 제공자만 등록한다")
    void onlyConfiguredProviders() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("KAKAO_CLIENT_ID", "kakao-id")
                .withProperty("KAKAO_CLIENT_SECRET", "kakao-secret");

        ClientRegistrationRepository repository = repository(env);

        assertThat(repository.findByRegistrationId("kakao")).isNotNull();
        assertThat(repository.findByRegistrationId("google")).isNull();
    }
}