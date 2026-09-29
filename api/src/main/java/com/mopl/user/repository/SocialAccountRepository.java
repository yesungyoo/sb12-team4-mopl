package com.mopl.user.repository;

import com.mopl.core.common.enums.SocialProvider;
import com.mopl.core.domain.user.entity.SocialAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

    /** 이미 연동된 소셜 계정인지 확인 (provider 가 발급한 고유 ID 기준) */
    Optional<SocialAccount> findByProviderAndProviderId(SocialProvider provider, String providerId);
}