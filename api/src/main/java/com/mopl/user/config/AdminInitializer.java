package com.mopl.user.config;

import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 서버 시작 시 어드민 계정을 자동으로 초기화한다. (팀 문서: "초기화: 서버 시작 시 어드민 계정을 자동으로 초기화합니다.")
 * 이미 존재하면 아무것도 하지 않아 여러 번 재시작해도 중복 생성되지 않는다.
 *
 * NOTE: 비밀번호를 코드/yml에 평문으로 커밋하면 안 되므로, 기본값은 로컬 개발 편의용이고
 * 운영 환경에서는 반드시 환경변수(ADMIN_EMAIL, ADMIN_PASSWORD)로 덮어써야 한다.
 *   application.yml 예:
 *     admin:
 *       email: ${ADMIN_EMAIL:admin@mopl.com}
 *       password: ${ADMIN_PASSWORD:Admin1234!}
 *       name: ${ADMIN_NAME:관리자}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email:admin@mopl.com}")
    private String adminEmail;

    @Value("${admin.password:Admin1234!}")
    private String adminPassword;

    @Value("${admin.name:관리자}")
    private String adminName;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(adminEmail)) {
            log.info("어드민 계정이 이미 존재합니다. email={}", adminEmail);
            return;
        }

        User admin = new User(
                adminEmail,
                passwordEncoder.encode(adminPassword),
                adminName,
                null,
                UserRole.ADMIN
        );
        userRepository.save(admin);
        log.info("어드민 계정을 초기화했습니다. email={}", adminEmail);
    }
}