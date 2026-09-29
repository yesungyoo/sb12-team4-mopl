package com.mopl.auth.service;

import com.mopl.auth.exception.SocialLoginException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.enums.SocialProvider;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.SocialAccount;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.repository.SocialAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인 계정 처리.
 *
 * 정책(팀 논의 결과 반영):
 * - 이미 연동된 소셜 계정이면 그 사용자로 로그인
 * - 처음 보는 소셜 계정인데 같은 이메일의 기존 계정이 있으면 자동 연동하지 않고 거절
 *   (users.email 유니크 제약 때문에 별개 계정 생성도 불가. 기존 계정 인증 후 선택적으로 연동하는
 *   방식은 후속 이슈로 진행 예정)
 * - 이메일이 처음이면 신규 가입 (비밀번호 없는 소셜 전용 계정)
 */
@Service
@RequiredArgsConstructor
public class SocialAuthService {

    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_IMAGE_URL_LENGTH = 500;

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;

    public record SocialLoginResult(UUID userId, String email, UserRole role) {
    }

    @Transactional
    public SocialLoginResult loginOrRegister(
            SocialProvider provider, String providerId, String email, boolean emailVerified,
            String name, String pictureUrl
    ) {
        // 리뷰 반영: 이메일 소유가 확인되지 않은 소셜 계정은 이미 연동된 계정이라도 로그인을 거절한다.
        // (이전에는 linked 분기가 먼저 반환돼서 이미 연동된 계정은 이 검증을 건너뛰는 문제가 있었음)
        if (email == null || email.isBlank() || !emailVerified) {
            throw new SocialLoginException(UserErrorCode.SOCIAL_EMAIL_NOT_VERIFIED);
        }

        Optional<SocialAccount> linked = socialAccountRepository.findByProviderAndProviderId(provider, providerId);
        if (linked.isPresent()) {
            User user = linked.get().getUser();
            if (user.getDeletedAt() != null) {
                throw new SocialLoginException(UserErrorCode.USER_WITHDRAWN);
            }
            if (user.isLocked()) {
                throw new SocialLoginException(UserErrorCode.USER_LOCKED);
            }
            return new SocialLoginResult(user.getId(), user.getEmail(), user.getRole());
        }

        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new SocialLoginException(UserErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED);
        }

        try {
            User user = userRepository.saveAndFlush(
                    new User(email, null, normalizeName(name, email), normalizeImageUrl(pictureUrl), UserRole.USER)
            );
            socialAccountRepository.saveAndFlush(new SocialAccount(user, provider, providerId));
            return new SocialLoginResult(user.getId(), user.getEmail(), user.getRole());
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 같은 이메일이 먼저 가입된 경우(DB 유니크 제약)
            throw new SocialLoginException(UserErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED);
        }
    }

    /** users.name 은 NOT NULL, 최대 50자 */
    private String normalizeName(String name, String email) {
        String base = (name == null || name.isBlank()) ? email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length()) : name;
        return base.length() > MAX_NAME_LENGTH ? base.substring(0, MAX_NAME_LENGTH) : base;
    }

    /** profile_image_url 은 최대 500자. 넘으면 저장하지 않는다 */
    private String normalizeImageUrl(String url) {
        return (url == null || url.length() > MAX_IMAGE_URL_LENGTH) ? null : url;
    }
}