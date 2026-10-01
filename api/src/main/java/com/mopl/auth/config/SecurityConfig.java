package com.mopl.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.filter.JwtAuthenticationFilter;
import com.mopl.auth.handler.LoginFailureHandler;
import com.mopl.auth.handler.LoginSuccessHandler;
import com.mopl.auth.handler.LogoutSuccessHandlerImpl;
import com.mopl.auth.handler.OAuth2LoginHandler;
import com.mopl.auth.jwt.AuthCookies;
import com.mopl.infrastructure.security.jwt.JwtProperties;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import com.mopl.auth.provider.EmailPasswordAuthenticationProvider;
import com.mopl.auth.redis.TokenRedisService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // @PreAuthorize("hasRole('ADMIN')") 등 사용을 위해 필요
@EnableConfigurationProperties(JwtProperties.class) // JwtProperties 를 빈으로 등록
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;
    private final EmailPasswordAuthenticationProvider emailPasswordAuthenticationProvider;
    private final LoginSuccessHandler loginSuccessHandler;
    private final LoginFailureHandler loginFailureHandler;
    private final LogoutSuccessHandlerImpl logoutSuccessHandler;
    private final OAuth2LoginHandler oAuth2LoginHandler;
    // 구글 클라이언트 설정(환경변수)이 있을 때만 빈이 존재한다. 없으면 oauth2Login 을 켜지 않는다.
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // JwtAuthenticationFilter 는 @Component 가 아니라 여기서 직접 생성한다.
        // (Filter 빈으로 자동 스캔되면 @WebMvcTest slice 테스트에서 의존성을 못 찾아 컨텍스트 로딩이 깨짐)
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtTokenProvider, tokenRedisService);

        http
                // JWT 를 쿠키로 주고받으므로 CSRF 방어가 필요함.
                // 쿠키 이름 XSRF-TOKEN / 헤더 이름 X-XSRF-TOKEN 은 CookieCsrfTokenRepository 기본값과 정확히 일치.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // 기본 핸들러(XorCsrfTokenRequestAttributeHandler)는 매 요청마다 토큰을 XOR 인코딩해서
                        // BREACH 공격을 방어하는데, 이 방식은 "쿠키의 원본 값을 그대로 헤더에 실어 보내는"
                        // 쿠키 기반 CSRF 흐름(SPA, curl 등)과 맞지 않아 정상 요청도 403으로 막힌다.
                        // 쿠키 원본 값을 그대로 비교하는 CsrfTokenRequestAttributeHandler 로 명시해야 함.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(emailPasswordAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/sign-in", "/api/auth/refresh", "/api/auth/csrf-token", "/api/auth/reset-password").permitAll()
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll() // 회원가입
                        .anyRequest().authenticated()
                )
                // 로그인은 Swagger 스펙상 SecurityFilterChain(formLogin)에서 처리.
                // application/x-www-form-urlencoded 로 username/password 를 받는 게 기본 동작이라 별도 설정 불필요.
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/sign-in")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(loginSuccessHandler)
                        .failureHandler(loginFailureHandler)
                        .permitAll()
                )
                // 로그아웃도 마찬가지로 Swagger 스펙상 SecurityFilterChain(logout)에서 처리.
                .logout(logout -> logout
                        .logoutUrl("/api/auth/sign-out")
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .deleteCookies(AuthCookies.ACCESS_TOKEN, AuthCookies.REFRESH_TOKEN)
                )
                // 인증 안 된 상태로 보호된 자원에 접근하면 기본 로그인 페이지로 리다이렉트하는 대신 401 JSON 응답.
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getWriter(), Map.of(
                                    "exceptionName", "UnauthorizedException",
                                    "message", "인증이 필요합니다.",
                                    "details", Map.of()
                            ));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getWriter(), Map.of(
                                    "exceptionName", "AccessDeniedException",
                                    "message", "접근 권한이 없습니다.",
                                    "details", Map.of()
                            ));
                        })
                )
                // LogoutFilter 는 UsernamePasswordAuthenticationFilter 보다 앞서 실행되는 필터라,
                // JwtAuthenticationFilter 를 그 앞에 둬야 /sign-out 요청에서도 SecurityContext 에
                // AuthUser 가 세팅된 상태로 LogoutSuccessHandlerImpl 이 실행된다.
                .addFilterBefore(jwtAuthenticationFilter, LogoutFilter.class);

        // 소셜 로그인(구글). 구글 클라이언트 설정이 없는 환경(CI, 설정 안 한 로컬)에서는 켜지 않는다.
        // 참고: OAuth2 로그인은 state 검증을 위해 콜백이 돌아올 때까지 세션을 잠깐 사용한다(JSESSIONID 발급).
        // 로그인이 끝나면 우리 JWT 쿠키로 인증하므로 세션은 더 쓰지 않는다.
        if (clientRegistrationRepository.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .successHandler(oAuth2LoginHandler)
                    .failureHandler(oAuth2LoginHandler)
            );
        }

        return http.build();
    }
}