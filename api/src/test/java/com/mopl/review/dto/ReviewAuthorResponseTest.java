package com.mopl.review.dto;

import com.mopl.core.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewAuthorResponseTest {

    @Test
    @DisplayName("활성 사용자는 기존 이름과 프로필 이미지를 반환한다")
    void fromActiveUser() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);

        when(user.getId()).thenReturn(userId);
        when(user.getDeletedAt()).thenReturn(null);
        when(user.getName()).thenReturn("리뷰작성자");
        when(user.getProfileImageUrl())
                .thenReturn("https://example.com/profile.png");

        ReviewAuthorResponse response = ReviewAuthorResponse.from(user);

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.name()).isEqualTo("리뷰작성자");
        assertThat(response.profileImageUrl())
                .isEqualTo("https://example.com/profile.png");
    }

    @Test
    @DisplayName("탈퇴 사용자는 이름을 탈퇴한 사용자로 표시하고 프로필 이미지를 숨긴다")
    void fromDeletedUser() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);

        when(user.getId()).thenReturn(userId);
        when(user.getDeletedAt()).thenReturn(LocalDateTime.now());

        ReviewAuthorResponse response = ReviewAuthorResponse.from(user);

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.name()).isEqualTo("탈퇴한 사용자");
        assertThat(response.profileImageUrl()).isNull();
    }
}