package com.mopl.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordEncoder 를 SecurityConfig 안에 두면 순환참조가 생긴다:
 * SecurityConfig(생성자 주입) -> EmailPasswordAuthenticationProvider -> PasswordEncoder -> (SecurityConfig 의 @Bean).
 * 그래서 별도 설정 클래스로 분리.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}