package com.mopl.user.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.user.dto.FollowResponse;
import com.mopl.user.service.FollowService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Swagger 명세(팔로우 관리)와 프론트가 호출하는 경로/상태 코드/응답 형태가 맞는지 확인한다.
 * 보안 필터 없이 컨트롤러만 검증하는 standalone 테스트라서, 현재 로그인 사용자는 SecurityUtil 을 mock 한다.
 */
class FollowControllerTest {

    private final UUID requesterId = UUID.randomUUID();

    private FollowService followService;
    private MockMvc mockMvc;
    private MockedStatic<SecurityUtil> securityUtil;

    @BeforeEach
    void setUp() {
        followService = mock(FollowService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FollowController(followService)).build();
        securityUtil = mockStatic(SecurityUtil.class);
        securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(requesterId);
    }

    @AfterEach
    void tearDown() {
        securityUtil.close();
    }

    @Test
    @DisplayName("POST /api/follows: 바디의 followeeId 로 팔로우하고 FollowDto(id, followeeId, followerId)를 201 로 반환한다")
    void follow() throws Exception {
        UUID followeeId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        when(followService.follow(requesterId, followeeId))
                .thenReturn(new FollowResponse(followId, followeeId, requesterId));

        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followeeId\":\"" + followeeId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(followId.toString()))
                .andExpect(jsonPath("$.followeeId").value(followeeId.toString()))
                .andExpect(jsonPath("$.followerId").value(requesterId.toString()));
    }

    @Test
    @DisplayName("POST /api/follows: followeeId 가 없으면 400")
    void followWithoutFolloweeId() throws Exception {
        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/follows/{followId}: 요청자 기준으로 팔로우를 취소하고 204 를 반환한다")
    void unfollow() throws Exception {
        UUID followId = UUID.randomUUID();

        mockMvc.perform(delete("/api/follows/{followId}", followId))
                .andExpect(status().isNoContent());

        verify(followService).unfollow(requesterId, followId);
    }

    @Test
    @DisplayName("GET /api/follows/count: 팔로워 수를 숫자로 반환한다")
    void count() throws Exception {
        UUID followeeId = UUID.randomUUID();
        when(followService.countFollowers(followeeId)).thenReturn(3L);

        mockMvc.perform(get("/api/follows/count").param("followeeId", followeeId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string("3"));
    }

    @Test
    @DisplayName("GET /api/follows/followed-by-me: 팔로우 중이면 FollowDto 를 반환한다")
    void followedByMe() throws Exception {
        UUID followeeId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        when(followService.getFollowedByMe(requesterId, followeeId))
                .thenReturn(new FollowResponse(followId, followeeId, requesterId));

        mockMvc.perform(get("/api/follows/followed-by-me").param("followeeId", followeeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(followId.toString()))
                .andExpect(jsonPath("$.followeeId").value(followeeId.toString()))
                .andExpect(jsonPath("$.followerId").value(requesterId.toString()));
    }

    @Test
    @DisplayName("GET /api/follows/count: followeeId 가 없으면 400")
    void countWithoutFolloweeId() throws Exception {
        mockMvc.perform(get("/api/follows/count"))
                .andExpect(status().isBadRequest());
    }
}