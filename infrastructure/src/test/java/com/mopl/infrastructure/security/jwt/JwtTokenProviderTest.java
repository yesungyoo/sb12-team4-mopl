package com.mopl.infrastructure.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.core.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void setUp() {
		JwtProperties jwtProperties = new JwtProperties(
			"test-secret-key-test-secret-key-test-secret-key",
			3600L,
			86400L
		);

		jwtTokenProvider = new JwtTokenProvider(jwtProperties);
	}

	@Test
	void accessToken_canBeParsedAsAccessToken() {
		UUID userId = UUID.randomUUID();

		String token = jwtTokenProvider.createAccessToken(
			userId,
			"user@test.com",
			UserRole.USER
		);

		Claims claims = jwtTokenProvider.parseAccessTokenClaims(token);

		assertThat(jwtTokenProvider.getUserId(claims)).isEqualTo(userId);
		assertThat(jwtTokenProvider.getEmail(claims)).isEqualTo("user@test.com");
		assertThat(jwtTokenProvider.getRole(claims)).isEqualTo(UserRole.USER);
	}

	@Test
	void refreshToken_cannotBeParsedAsAccessToken() {
		UUID userId = UUID.randomUUID();

		String refreshToken =
			jwtTokenProvider.createRefreshToken(userId);

		assertThatThrownBy(() ->
			jwtTokenProvider.parseAccessTokenClaims(refreshToken)
		)
			.isInstanceOf(JwtException.class)
			.hasMessage("Access token is required");
	}
}
