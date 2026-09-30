package com.mopl.auth.social;

import com.mopl.core.common.enums.SocialProvider;
import java.util.Map;

/**
 * 제공자별 사용자 정보 응답(attributes)을 SocialUserInfo 로 변환한다.
 * 스프링 컨텍스트 없이 단위 테스트할 수 있도록 순수 클래스로 만들었다.
 */
public final class SocialUserInfoMapper {

    private static final int MAX_NICKNAME_LENGTH = 50;
    private static final String KAKAO_EMAIL_DOMAIN = "@kakao.com";

    private SocialUserInfoMapper() {
    }

    /**
     * @throws IllegalArgumentException 지원하지 않는 제공자이거나, 소셜 계정 식별자가 없을 때
     */
    public static SocialUserInfo map(String registrationId, Map<String, Object> attributes) {
        return switch (registrationId) {
            case "google" -> google(attributes);
            case "kakao" -> kakao(attributes);
            default -> throw new IllegalArgumentException("지원하지 않는 소셜 로그인 제공자입니다: " + registrationId);
        };
    }

    private static SocialUserInfo google(Map<String, Object> attributes) {
        String id = requireId(asString(attributes.get("sub")));
        return new SocialUserInfo(
                SocialProvider.GOOGLE,
                id,
                asString(attributes.get("email")),
                Boolean.TRUE.equals(attributes.get("email_verified")),
                asString(attributes.get("name")),
                asString(attributes.get("picture"))
        );
    }

    /**
     * 카카오는 로그인 API 제약으로 이메일을 가져올 수 없다. 요구사항에 따라
     * {닉네임}_{회원ID}@kakao.com 형태의 가상 이메일을 만든다. 우리가 만든 값이라 소유 확인은 필요 없어서
     * emailVerified 는 true 로 둔다. 회원ID 는 카카오가 주는 회원번호(id)를 쓴다.
     */
    private static SocialUserInfo kakao(Map<String, Object> attributes) {
        String id = requireId(asString(attributes.get("id")));
        String nickname = kakaoNickname(attributes);
        return new SocialUserInfo(
                SocialProvider.KAKAO,
                id,
                virtualEmail(nickname, id),
                true,
                nickname.isBlank() ? null : nickname,
                null
        );
    }

    /** 닉네임은 kakao_account.profile.nickname 에 있고, 없으면 예전 위치인 properties.nickname 을 본다 */
    private static String kakaoNickname(Map<String, Object> attributes) {
        if (attributes.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile) {
            String nickname = asString(profile.get("nickname"));
            if (nickname != null && !nickname.isBlank()) {
                return nickname;
            }
        }
        if (attributes.get("properties") instanceof Map<?, ?> properties) {
            String nickname = asString(properties.get("nickname"));
            if (nickname != null && !nickname.isBlank()) {
                return nickname;
            }
        }
        return "";
    }

    /** 공백과 @ 는 이메일로 쓸 수 없어서 제거하고, 너무 길면 자른다. 닉네임이 비면 "kakao" 를 쓴다 */
    private static String virtualEmail(String nickname, String id) {
        String cleaned = nickname.replaceAll("[\\s@]", "");
        if (cleaned.length() > MAX_NICKNAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NICKNAME_LENGTH);
        }
        if (cleaned.isEmpty()) {
            cleaned = "kakao";
        }
        return cleaned + "_" + id + KAKAO_EMAIL_DOMAIN;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("소셜 계정 식별자가 없습니다.");
        }
        return id;
    }

    /** 카카오의 id 는 숫자(Integer/Long)로 오기 때문에 문자열로 바꿔서 다룬다 */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}