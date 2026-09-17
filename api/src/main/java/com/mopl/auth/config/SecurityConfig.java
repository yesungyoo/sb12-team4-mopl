package com.mopl.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.filter.JwtAuthenticationFilter;
import com.mopl.auth.handler.LoginFailureHandler;
import com.mopl.auth.handler.LoginSuccessHandler;
import com.mopl.auth.handler.LogoutSuccessHandlerImpl;
import com.mopl.auth.jwt.AuthCookies;
import com.mopl.auth.jwt.JwtProperties;
import com.mopl.auth.jwt.JwtTokenProvider;
import com.mopl.auth.provider.EmailPasswordAuthenticationProvider;
import com.mopl.auth.redis.TokenRedisService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

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
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(emailPasswordAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/sign-in", "/api/auth/refresh", "/api/auth/csrf-token").permitAll()
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

        return http.build();
    }
}