package com.mopl.auth.filter;

import com.mopl.auth.dto.AuthUser;
import com.mopl.auth.jwt.AuthCookies;
import com.mopl.infrastructure.security.jwt.JwtTokenProvider;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.core.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * "로그인 사용자 식별" 의 핵심.
 * 1) 쿠키에서 access token 추출
 * 2) 서명/만료 검증
 * 3) Redis 무효화 체크 (잠금/권한변경/탈퇴/로그아웃 시 여기서 걸러짐 -> "강제 로그아웃" 구현부)
 * 4) 통과하면 SecurityContext 에 AuthUser 를 principal 로 세팅
 *
 * NOTE: 일부러 @Component 를 안 붙였습니다. Filter 빈은 @WebMvcTest(Controller slice test) 에서
 * 무조건 스캔 대상이 되는데, 이 필터의 생성자 의존성(JwtTokenProvider, TokenRedisService)이
 * slice 테스트 컨텍스트에는 없어서 ContentControllerTest 등 Security 와 무관한 테스트까지
 * ApplicationContext 로딩에 실패했었습니다. 대신 SecurityConfig 에서 직접 new 로 생성해서
 * 필터체인에 등록합니다 (아래 SecurityConfig 참고).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;
	private final SecurityContextRepository securityContextRepository;

	public JwtAuthenticationFilter(
		JwtTokenProvider jwtTokenProvider,
		TokenRedisService tokenRedisService,
		SecurityContextRepository securityContextRepository
	) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.tokenRedisService = tokenRedisService;
		this.securityContextRepository = securityContextRepository;
	}

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws IOException, ServletException {
        try {
			extractAccessToken(request)
				.ifPresent(token -> authenticate(token, request, response));
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

	private void authenticate(
		String token,
		HttpServletRequest request,
		HttpServletResponse response
	) {
        Claims claims = jwtTokenProvider.parseAccessTokenClaims(token);
        UUID userId = jwtTokenProvider.getUserId(claims);
        Instant issuedAt = jwtTokenProvider.getIssuedAt(claims);

        if (tokenRedisService.isInvalidated(userId, issuedAt)) {
            return;
        }

        String email = jwtTokenProvider.getEmail(claims);
        UserRole role = jwtTokenProvider.getRole(claims);
        AuthUser authUser = new AuthUser(userId, email, role);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
		var authentication =
			new UsernamePasswordAuthenticationToken(authUser, null, authorities);

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);

		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
    }

  private Optional<String> extractAccessToken(HttpServletRequest request) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      return Optional.of(authHeader.substring(7));
    }

    if (request.getCookies() == null) {
      return Optional.empty();
    }
    for (Cookie cookie : request.getCookies()) {
      if (AuthCookies.ACCESS_TOKEN.equals(cookie.getName())) {
        return Optional.of(cookie.getValue());
      }
    }
    return Optional.empty();
  }
}