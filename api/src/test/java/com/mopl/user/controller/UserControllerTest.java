package com.mopl.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.auth.config.SecurityConfig;
import com.mopl.auth.handler.LoginFailureHandler;
import com.mopl.auth.handler.LoginSuccessHandler;
import com.mopl.auth.handler.LogoutSuccessHandlerImpl;
import com.mopl.auth.jwt.JwtTokenProvider;
import com.mopl.auth.provider.EmailPasswordAuthenticationProvider;
import com.mopl.auth.redis.TokenRedisService;
import com.mopl.core.common.enums.UserRole;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.service.UserService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 회원가입은 Security(인증/CSRF)와 직접 관련된 엔드포인트라 addFilters=false 로 끄지 않고,
 * 실제 Security 필터체인을 태운 채로 검증한다.
 *
 * SecurityConfig 를 @Import 하면 그 생성자가 필요로 하는 협력자들을
 * 이 slice 컨텍스트가 못 찾으므로 @MockitoBean 으로 채워준다.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private TokenRedisService tokenRedisService;

    @MockitoBean
    private EmailPasswordAuthenticationProvider emailPasswordAuthenticationProvider;

    @MockitoBean
    private LoginSuccessHandler loginSuccessHandler;

    @MockitoBean
    private LoginFailureHandler loginFailureHandler;

    @MockitoBean
    private LogoutSuccessHandlerImpl logoutSuccessHandler;

    @Test
    @DisplayName("CSRF 토큰 없이 회원가입을 요청하면 403을 반환한다")
    void signUpWithoutCsrfReturnsForbidden() throws Exception {
        UserCreateRequest request = new UserCreateRequest("홍길동", "test@mopl.com", "password123");

        mockMvc.perform(
                        post("/api/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CSRF 토큰을 포함해 회원가입에 성공하면 201을 반환한다")
    void signUpWithCsrfReturnsCreated() throws Exception {
        UserCreateRequest request = new UserCreateRequest("홍길동", "test@mopl.com", "password123");
        UserResponse response = new UserResponse(
                UUID.randomUUID(),
                LocalDateTime.now(),
                "test@mopl.com",
                "홍길동",
                null,
                UserRole.USER,
                false
        );

        when(userService.signUp(any(UserCreateRequest.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/users")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("test@mopl.com"))
                .andExpect(jsonPath("$.name").value("홍길동"));
    }
}