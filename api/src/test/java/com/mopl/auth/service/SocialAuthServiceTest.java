package com.mopl.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.auth.exception.SocialLoginException;
import com.mopl.auth.service.SocialAuthService.SocialLoginResult;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.enums.SocialProvider;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.SocialAccount;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.repository.SocialAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SocialAuthServiceTest {

    private static final SocialProvider GOOGLE = SocialProvider.GOOGLE;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @InjectMocks
    private SocialAuthService socialAuthService;

    private User userWithId(String email) {
        User user = new User(email, null, "홍길동", null, UserRole.USER);
        // 저장된 적 없는 픽스처라 id 가 null 이므로 리플렉션으로 채운다
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    @DisplayName("이미 연동된 소셜 계정이면 그 사용자로 로그인하고 새로 저장하지 않는다")
    void alreadyLinked() {
        User user = userWithId("linked@mopl.com");
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-1"))
                .thenReturn(Optional.of(new SocialAccount(user, GOOGLE, "sub-1")));

        SocialLoginResult result =
                socialAuthService.loginOrRegister(GOOGLE, "sub-1", "linked@mopl.com", true, "홍길동", null);

        assertThat(result.userId()).isEqualTo(user.getId());
        assertThat(result.email()).isEqualTo("linked@mopl.com");
        verify(userRepository, never()).saveAndFlush(any());
        verify(socialAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("리뷰 반영: 이미 연동된 계정이라도 이번 로그인의 이메일이 인증되지 않았으면 거절한다")
    void linkedButEmailNotVerifiedThisTime() {
        // 리뷰 반영(2차): 지금은 이메일 검증이 linked 조회보다 먼저라 이 stub이 실제로는 안 쓰인다.
        // lenient() 로 "안 쓰여도 에러 내지 말라"고 표시해두고, 나중에 누가 검증 순서를
        // (linked 먼저 → 이메일 검증) 으로 되돌리는 회귀가 생기면 이 stub이 실제로 쓰이면서
        // 로그인이 성공해버려 테스트가 실패로 잡아낸다.
        User user = userWithId("linked2@mopl.com");
        lenient().when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-10"))
                .thenReturn(Optional.of(new SocialAccount(user, GOOGLE, "sub-10")));

        assertRejectedWith(UserErrorCode.SOCIAL_EMAIL_NOT_VERIFIED,
                () -> socialAuthService.loginOrRegister(GOOGLE, "sub-10", "linked2@mopl.com", false, "홍길동", null));
    }

    @Test
    @DisplayName("연동된 계정이 잠겨 있으면 USER_LOCKED 로 거절한다")
    void linkedButLocked() {
        User user = userWithId("locked@mopl.com");
        ReflectionTestUtils.setField(user, "locked", true);
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-2"))
                .thenReturn(Optional.of(new SocialAccount(user, GOOGLE, "sub-2")));

        assertRejectedWith(UserErrorCode.USER_LOCKED,
                () -> socialAuthService.loginOrRegister(GOOGLE, "sub-2", "locked@mopl.com", true, "홍길동", null));
    }

    @Test
    @DisplayName("처음 보는 소셜 계정이고 이메일도 새것이면 신규 가입한다 (비밀번호 없는 USER 계정)")
    void newSignUp() {
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-3")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailAndDeletedAtIsNull("new@mopl.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        SocialLoginResult result =
                socialAuthService.loginOrRegister(GOOGLE, "sub-3", "new@mopl.com", true, "새사용자", null);

        assertThat(result.email()).isEqualTo("new@mopl.com");
        assertThat(result.role()).isEqualTo(UserRole.USER);
        assertThat(result.userId()).isNotNull();
        verify(socialAccountRepository).saveAndFlush(any(SocialAccount.class));
    }

    @Test
    @DisplayName("같은 이메일의 기존 계정이 있으면 자동 연동하지 않고 SOCIAL_EMAIL_ALREADY_REGISTERED 로 거절한다")
    void emailAlreadyRegistered() {
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-4")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailAndDeletedAtIsNull("exists@mopl.com")).thenReturn(true);

        assertRejectedWith(UserErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED,
                () -> socialAuthService.loginOrRegister(GOOGLE, "sub-4", "exists@mopl.com", true, "홍길동", null));

        verify(userRepository, never()).saveAndFlush(any());
        verify(socialAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("구글이 이메일 소유를 확인해주지 않은 계정은 SOCIAL_EMAIL_NOT_VERIFIED 로 거절한다")
    void emailNotVerified() {
        assertRejectedWith(UserErrorCode.SOCIAL_EMAIL_NOT_VERIFIED,
                () -> socialAuthService.loginOrRegister(GOOGLE, "sub-5", "unverified@mopl.com", false, "홍길동", null));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이메일이 없는 소셜 계정은 SOCIAL_EMAIL_NOT_VERIFIED 로 거절한다")
    void missingEmail() {
        assertRejectedWith(UserErrorCode.SOCIAL_EMAIL_NOT_VERIFIED,
                () -> socialAuthService.loginOrRegister(GOOGLE, "sub-6", null, true, "홍길동", null));
    }

    @Test
    @DisplayName("동시 가입으로 DB 유니크 제약에 걸리면 SOCIAL_EMAIL_ALREADY_REGISTERED 로 거절한다")
    void concurrentSignUp() {
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-7")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailAndDeletedAtIsNull("race@mopl.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertRejectedWith(UserErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED,
                () -> socialAuthService.loginOrRegister(GOOGLE, "sub-7", "race@mopl.com", true, "홍길동", null));
    }

    @Test
    @DisplayName("이름이 없으면 이메일 앞부분을, 50자를 넘으면 50자로 잘라 저장한다")
    void nameFallbackAndTruncate() {
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-8")).thenReturn(Optional.empty());
        when(socialAccountRepository.findByProviderAndProviderId(GOOGLE, "sub-9")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailAndDeletedAtIsNull(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        socialAuthService.loginOrRegister(GOOGLE, "sub-8", "nobody@mopl.com", true, null, null);
        socialAuthService.loginOrRegister(GOOGLE, "sub-9", "long@mopl.com", true, "가".repeat(80), null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(0).getName()).isEqualTo("nobody");
        assertThat(captor.getAllValues().get(1).getName()).hasSize(50);
    }

    private void assertRejectedWith(UserErrorCode expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(SocialLoginException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}