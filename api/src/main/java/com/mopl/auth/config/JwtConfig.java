package com.mopl.auth.config;

import com.mopl.infrastructure.security.jwt.JwtProperties;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.mopl.infrastructure.security.token.AccessTokenInvalidationService;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class JwtConfig {

	@Bean
	public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
		return new JwtTokenProvider(jwtProperties);
	}

	@Bean
	public AccessTokenInvalidationService accessTokenInvalidationService(
		StringRedisTemplate redisTemplate,
		JwtProperties jwtProperties
	) {
		return new AccessTokenInvalidationService(
			redisTemplate,
			jwtProperties.accessTokenExpirationSeconds()
		);
	}
}
