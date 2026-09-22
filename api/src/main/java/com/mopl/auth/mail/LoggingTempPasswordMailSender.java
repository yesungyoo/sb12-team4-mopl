package com.mopl.auth.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TODO: 메일 발송 인프라(SMTP) 연동 전까지의 임시 구현.
 * 실제로 이메일을 보내지 않고 로그만 남긴다.
 *
 * 리뷰 반영: 임시비밀번호 원문이 로그에 그대로 남던 부분을 제거했다.
 * (Profile 제한은 다른 도메인의 @SpringBootTest 전체 컨텍스트 테스트가 프로파일 지정 없이
 *  이 빈을 필요로 해서 깨질 수 있어 적용하지 않음 - 리뷰어 후속 코멘트에서도 평문 로그 제거만
 *  지금 필요하다고 정리됨)
 * SMTP 연동 시 이 클래스를 지우고 JavaMailSender 기반 구현체로 교체.
 */
@Slf4j
@Component
public class LoggingTempPasswordMailSender implements TempPasswordMailSender {

    @Override
    public void send(String toEmail, String tempPassword) {
        log.warn("[TODO: 메일 인프라 미연동] {} 로 임시 비밀번호 발송 시도 (실제 발송 안 됨)", toEmail);
    }
}