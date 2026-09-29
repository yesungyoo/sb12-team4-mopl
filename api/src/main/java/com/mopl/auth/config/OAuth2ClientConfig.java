package com.mopl.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * 환경변수 GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET 이 둘 다 비어있지 않을 때만 구글 클라이언트를 등록한다.
 *
 * application.yml 의 spring.security.oauth2.client.registration.google 로 등록하지 않는 이유:
 * 값이 비어있으면 Spring 이 "clientId cannot be empty" 로 애플리케이션 기동 자체를 실패시켜서,
 * 구글 설정이 없는 환경(CI, 다른 팀원의 로컬)이 전부 깨진다.
 * 이 방식이면 환경변수가 없을 땐 이 빈이 안 만들어지고, SecurityConfig 도 oauth2Login 을 켜지 않는다.
 *
 * 구글 콘솔에 등록할 리다이렉트 URI: {서버주소}/login/oauth2/code/google
 * 로그인 시작 주소: {서버주소}/oauth2/authorization/google
 */
@Configuration
@ConditionalOnExpression("'${GOOGLE_CLIENT_ID:}' != '' && '${GOOGLE_CLIENT_SECRET:}' != ''")
public class OAuth2ClientConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(Environment env) {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(env.getRequiredProperty("GOOGLE_CLIENT_ID"))
                .clientSecret(env.getRequiredProperty("GOOGLE_CLIENT_SECRET"))
                .scope("email", "profile")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }
}