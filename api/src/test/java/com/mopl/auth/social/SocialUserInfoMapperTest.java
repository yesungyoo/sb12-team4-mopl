package com.mopl.auth.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.core.common.enums.SocialProvider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SocialUserInfoMapperTest {

    @Nested
    @DisplayName("구글")
    class Google {

        @Test
        @DisplayName("sub, email, email_verified, name, picture 를 그대로 옮긴다")
        void map() {
            Map<String, Object> attributes = Map.of(
                    "sub", "1234567890",
                    "email", "user@gmail.com",
                    "email_verified", true,
                    "name", "홍길동",
                    "picture", "https://example.com/p.png"
            );

            SocialUserInfo info = SocialUserInfoMapper.map("google", attributes);

            assertThat(info.provider()).isEqualTo(SocialProvider.GOOGLE);
            assertThat(info.providerId()).isEqualTo("1234567890");
            assertThat(info.email()).isEqualTo("user@gmail.com");
            assertThat(info.emailVerified()).isTrue();
            assertThat(info.name()).isEqualTo("홍길동");
            assertThat(info.pictureUrl()).isEqualTo("https://example.com/p.png");
        }

        @Test
        @DisplayName("email_verified 가 없으면 인증되지 않은 것으로 본다")
        void emailVerifiedMissing() {
            SocialUserInfo info = SocialUserInfoMapper.map("google", Map.of("sub", "1", "email", "user@gmail.com"));

            assertThat(info.emailVerified()).isFalse();
        }

        @Test
        @DisplayName("sub 가 없으면 예외")
        void missingId() {
            assertThatThrownBy(() -> SocialUserInfoMapper.map("google", Map.of("email", "user@gmail.com")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("카카오")
    class Kakao {

        private Map<String, Object> attributes(Object id, String nickname) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("id", id);
            attributes.put("kakao_account", Map.of("profile", Map.of("nickname", nickname)));
            return attributes;
        }

        @Test
        @DisplayName("이메일이 없어서 {닉네임}_{회원번호}@kakao.com 가상 이메일을 만들고, 인증된 것으로 취급한다")
        void virtualEmail() {
            SocialUserInfo info = SocialUserInfoMapper.map("kakao", attributes(123456789L, "홍길동"));

            assertThat(info.provider()).isEqualTo(SocialProvider.KAKAO);
            assertThat(info.providerId()).isEqualTo("123456789");
            assertThat(info.email()).isEqualTo("홍길동_123456789@kakao.com");
            assertThat(info.emailVerified()).isTrue();
            assertThat(info.name()).isEqualTo("홍길동");
            assertThat(info.pictureUrl()).isNull();
        }

        @Test
        @DisplayName("id 가 Integer 로 와도 문자열 회원번호로 다룬다")
        void integerId() {
            SocialUserInfo info = SocialUserInfoMapper.map("kakao", attributes(42, "닉"));

            assertThat(info.providerId()).isEqualTo("42");
            assertThat(info.email()).isEqualTo("닉_42@kakao.com");
        }

        @Test
        @DisplayName("닉네임의 공백과 @ 는 이메일에서 제거한다 (이름은 원본 유지)")
        void sanitizeNickname() {
            SocialUserInfo info = SocialUserInfoMapper.map("kakao", attributes(1L, "홍 길@동"));

            assertThat(info.email()).isEqualTo("홍길동_1@kakao.com");
            assertThat(info.name()).isEqualTo("홍 길@동");
        }

        @Test
        @DisplayName("닉네임이 50자를 넘으면 이메일에는 50자까지만 쓴다")
        void truncateNickname() {
            SocialUserInfo info = SocialUserInfoMapper.map("kakao", attributes(7L, "가".repeat(80)));

            assertThat(info.email()).isEqualTo("가".repeat(50) + "_7@kakao.com");
        }

        @Test
        @DisplayName("kakao_account 에 닉네임이 없으면 properties.nickname 을 쓴다")
        void fallbackToProperties() {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("id", 5L);
            attributes.put("properties", Map.of("nickname", "옛날닉네임"));

            SocialUserInfo info = SocialUserInfoMapper.map("kakao", attributes);

            assertThat(info.email()).isEqualTo("옛날닉네임_5@kakao.com");
            assertThat(info.name()).isEqualTo("옛날닉네임");
        }

        @Test
        @DisplayName("닉네임이 전혀 없으면 kakao_{회원번호}@kakao.com 이고 이름은 비워둔다")
        void noNickname() {
            SocialUserInfo info = SocialUserInfoMapper.map("kakao", new HashMap<>(Map.of("id", 9L)));

            assertThat(info.email()).isEqualTo("kakao_9@kakao.com");
            assertThat(info.name()).isNull();
        }

        @Test
        @DisplayName("id 가 없으면 예외")
        void missingId() {
            assertThatThrownBy(() -> SocialUserInfoMapper.map("kakao", Map.of("kakao_account", Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("지원하지 않는 제공자면 예외")
    void unsupportedProvider() {
        assertThatThrownBy(() -> SocialUserInfoMapper.map("naver", Map.of("id", "1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}