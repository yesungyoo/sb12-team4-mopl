package com.mopl.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 에 아래와 같이 설정 필요:
 *
 * jwt:
 *   secret: {최소 256bit 이상의 랜덤 문자열, 환경변수/시크릿 매니저로 관리 권장}
 *   access-token-expiration-seconds: 1800     # 30분
 *   refresh-token-expiration-seconds: 1209600 # 14일
 *
 * NOTE: 이 빈은 @Component 로 등록하지 않는다. @ConfigurationProperties 를 붙인 레코드에
 * @Component 까지 붙이면 스프링이 일반 빈으로 오인해서 생성자 파라미터를 프로퍼티 값이 아니라
 * 다른 빈으로 주입하려다 실패한다. 대신 SecurityConfig 의 @EnableConfigurationProperties 로 등록한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpirationSeconds,
        long refreshTokenExpirationSeconds
) {
}