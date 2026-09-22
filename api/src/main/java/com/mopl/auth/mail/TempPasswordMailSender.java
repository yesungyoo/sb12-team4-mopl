package com.mopl.auth.mail;

/**
 * 메일 발송 인프라(SMTP 등)가 아직 프로젝트에 없어서, 실제 발송 대신 로그로 남기는
 * 임시 구현(LoggingTempPasswordMailSender)만 두고 인터페이스로 분리해뒀습니다.
 * 나중에 spring-boot-starter-mail 등을 붙이면, 이 인터페이스의 구현체만 하나 더 만들어서
 * 빈으로 교체하면 됩니다 (호출부인 AuthService 는 안 건드려도 됨).
 */
public interface TempPasswordMailSender {

    void send(String toEmail, String tempPassword);
}