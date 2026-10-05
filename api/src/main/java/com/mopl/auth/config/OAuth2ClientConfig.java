package com.mopl.auth.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * 환경변수가 설정된 제공자만 클라이언트로 등록한다.
 * - 구글: GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
 * - 카카오: KAKAO_CLIENT_ID(REST API 키) / KAKAO_CLIENT_SECRET
 * 둘 다 없으면 이 설정 자체가 꺼지고 SecurityConfig 도 oauth2Login 을 켜지 않는다.
 *
 * application.yml 의 spring.security.oauth2.client.registration 으로 등록하지 않는 이유:
 * 값이 비어있으면 Spring 이 "clientId cannot be empty" 로 애플리케이션 기동 자체를 실패시켜서,
 * 소셜 설정이 없는 환경(CI, 다른 팀원의 로컬)이 전부 깨진다.
 *
 * 리다이렉트 URI:
 * - 기본: {서버가 받은 요청의 baseUrl}/login/oauth2/code/{google|kakao}
 * - APP_OAUTH2_REDIRECT_BASE_URL(app.oauth2.redirect-base-url) 이 있으면
 *   {그 값}/login/oauth2/code/{google|kakao}
 *   배포 환경이 CloudFront → ALB(http) 구조처럼 서버가 받는 요청이 http 라서 {baseUrl} 이
 *   http://... 로 계산되는 경우, 제공자 콘솔에 등록한 https 주소와 달라져 KOE006 /
 *   redirect_uri_mismatch 가 난다. 이때 외부에서 보이는 주소(예: https://도메인)를 이 값으로 지정한다.
 *
 * 로그인 시작 주소: {서버주소}/oauth2/authorization/{google|kakao}
 */
@Configuration
@ConditionalOnExpression(
        "('${GOOGLE_CLIENT_ID:}' != '' && '${GOOGLE_CLIENT_SECRET:}' != '')"
                + " || ('${KAKAO_CLIENT_ID:}' != '' && '${KAKAO_CLIENT_SECRET:}' != '')"
)
public class OAuth2ClientConfig {

    private static final String DEFAULT_REDIRECT_URI = "{baseUrl}/{action}/oauth2/code/{registrationId}";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(Environment env) {
        String redirectUri = redirectUriTemplate(env);

        List<ClientRegistration> registrations = new ArrayList<>();
        if (isConfigured(env, "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET")) {
            registrations.add(google(env, redirectUri));
        }
        if (isConfigured(env, "KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET")) {
            registrations.add(kakao(env, redirectUri));
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    /** app.oauth2.redirect-base-url 이 없으면 Spring 기본 템플릿, 있으면 그 값을 기준으로 한 고정 주소 */
    static String redirectUriTemplate(Environment env) {
        String base = env.getProperty("app.oauth2.redirect-base-url");
        if (base == null || base.isBlank()) {
            return DEFAULT_REDIRECT_URI;
        }
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/login/oauth2/code/{registrationId}";
    }

    private boolean isConfigured(Environment env, String idKey, String secretKey) {
        String id = env.getProperty(idKey);
        String secret = env.getProperty(secretKey);
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
    }

    private ClientRegistration google(Environment env, String redirectUri) {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(env.getRequiredProperty("GOOGLE_CLIENT_ID"))
                .clientSecret(env.getRequiredProperty("GOOGLE_CLIENT_SECRET"))
                .redirectUri(redirectUri)
                .scope("email", "profile")
                .build();
    }

    /**
     * 스프링이 카카오를 기본 제공하지 않아서 주소를 직접 등록한다.
     * 카카오는 토큰 요청 때 client_id/client_secret 을 요청 본문(POST)으로 받는다.
     * 이메일은 가져올 수 없으므로 동의 항목은 닉네임(profile_nickname)만 요청한다.
     * (카카오 앱에서 설정하지 않은 동의 항목을 요청하면 KOE205 에러가 난다)
     */
    private ClientRegistration kakao(Environment env, String redirectUri) {
        return ClientRegistration.withRegistrationId("kakao")
                .clientId(env.getRequiredProperty("KAKAO_CLIENT_ID"))
                .clientSecret(env.getRequiredProperty("KAKAO_CLIENT_SECRET"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("profile_nickname")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id")
                .clientName("Kakao")
                .build();
    }
}