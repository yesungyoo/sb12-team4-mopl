package com.mopl.infrastructure.security.jwt;

import com.mopl.core.common.enums.UserRole;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * JWT 발급 / 파싱 담당.
 *
 * NOTE: build.gradle(api 모듈)에 아래 의존성이 필요합니다.
 *   implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
 *   runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
 *   runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
 */
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey secretKey;
    private final JwtProperties jwtProperties;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes());
    }

    public String createAccessToken(UUID userId, String email, UserRole role) {
        return createToken(userId, email, role, jwtProperties.accessTokenExpirationSeconds());
    }

    /**
     * refresh token 은 claim 없이 sub(userId) 만 담아 최소한으로 구성.
     * jti(고유 ID)를 넣는 이유: sub/iat/exp 만으로 구성하면 같은 초 안에 재발급된 두 토큰이
     * 완전히 동일한 문자열이 될 수 있어서(rotation 검증/보안상 위험), 매번 고유하도록 보장한다.
     */
    public String createRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(jwtProperties.refreshTokenExpirationSeconds())))
                .signWith(secretKey)
                .compact();
    }

    private String createToken(UUID userId, String email, UserRole role, long expirationSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(secretKey)
                .compact();
    }

    /** 서명/만료 검증 후 claims 반환. 위/변조되었거나 만료된 토큰이면 JwtException 발생. */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims parseAccessTokenClaims(String token) {
        Claims claims = parseClaims(token);

        String email = getEmail(claims);
        UserRole role = getRole(claims);

        if (email == null || email.isBlank() || role == null) {
            throw new JwtException("Access token is required");
        }

        return claims;
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID getUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public String getEmail(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }

    public UserRole getRole(Claims claims) {
        String role = claims.get(CLAIM_ROLE, String.class);
        return role == null ? null : UserRole.valueOf(role);
    }

    public Instant getIssuedAt(Claims claims) {
        return claims.getIssuedAt().toInstant();
    }

    public long getAccessTokenExpirationSeconds() {
        return jwtProperties.accessTokenExpirationSeconds();
    }

    public long getRefreshTokenExpirationSeconds() {
        return jwtProperties.refreshTokenExpirationSeconds();
    }
}