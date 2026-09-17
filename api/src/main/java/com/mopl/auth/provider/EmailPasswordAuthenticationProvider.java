package com.mopl.auth.provider;

import com.mopl.auth.dto.AuthUser;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Swagger 스펙상 로그인이 Spring Security formLogin(내장 필터)으로 처리되어야 해서,
 * 기존 AuthService.login() 로직을 AuthenticationProvider 형태로 옮긴 것.
 * 기본 DaoAuthenticationProvider 는 비밀번호 1개만 검사하므로, 임시 비밀번호 허용을 위해 직접 구현.
 */
@Component
@RequiredArgsConstructor
public class EmailPasswordAuthenticationProvider implements AuthenticationProvider {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String rawPassword = String.valueOf(authentication.getCredentials());

        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (user.isLocked()) {
            throw new LockedException("잠긴 계정입니다. 관리자에게 문의해주세요.");
        }

        if (!matchesPasswordOrTempPassword(user, rawPassword)) {
            throw new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        AuthUser principal = new AuthUser(user.getId(), user.getEmail(), user.getRole());
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private boolean matchesPasswordOrTempPassword(User user, String rawPassword) {
        if (user.getPassword() != null && passwordEncoder.matches(rawPassword, user.getPassword())) {
            return true;
        }
        boolean tempPasswordStillValid = user.getTempPassword() != null
                && user.getTempPasswordExpiredAt() != null
                && user.getTempPasswordExpiredAt().isAfter(LocalDateTime.now());
        return tempPasswordStillValid && passwordEncoder.matches(rawPassword, user.getTempPassword());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}