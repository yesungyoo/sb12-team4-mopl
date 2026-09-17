package com.mopl.user.controller;

import com.mopl.core.common.enums.UserRole;
import com.mopl.user.dto.CursorPageResponse;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserLockedUpdateRequest;
import com.mopl.user.dto.UserProfileUpdateRequest;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.dto.UserSearchCondition;
import com.mopl.auth.util.SecurityUtil;
import com.mopl.user.service.UserService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 회원가입. SecurityConfig 에서 permitAll 처리됨. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(@Valid @RequestBody UserCreateRequest request) {
        return userService.signUp(request);
    }

    /** 사용자 상세 조회 */
    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable UUID userId) {
        return userService.getUser(userId);
    }

    /** [어드민] 사용자 목록 조회 (커서 기반) */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public CursorPageResponse<UserResponse> getUsers(
            @RequestParam(required = false) String emailLike,
            @RequestParam(required = false) UserRole roleEqual,
            @RequestParam(required = false) Boolean isLocked,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String idAfter,
            @RequestParam int limit,
            @RequestParam(defaultValue = "ASCENDING") UserSearchCondition.SortDirection sortDirection,
            @RequestParam(defaultValue = "name") String sortBy
    ) {
        UserSearchCondition condition = new UserSearchCondition(
                emailLike, roleEqual, isLocked, cursor, idAfter, limit, sortDirection, sortBy);
        return userService.getUsers(condition);
    }

    /** 본인 프로필 변경 (이름 / 이미지) */
    @PatchMapping(value = "/{userId}", consumes = "multipart/form-data")
    public UserResponse updateProfile(
            @PathVariable UUID userId,
            @RequestPart UserProfileUpdateRequest request,
            @RequestPart(required = false) MultipartFile image
    ) {
        // TODO: image 가 존재하면 S3 업로드 후 URL 을 profileImageUrl 로 전달
        String profileImageUrl = null;
        UUID requesterId = SecurityUtil.getCurrentUserId();
        return userService.updateProfile(requesterId, userId, request.name(), profileImageUrl);
    }

    /** 본인 탈퇴 */
    @DeleteMapping("/{userId}")
    public void withdraw(@PathVariable UUID userId) {
        UUID requesterId = SecurityUtil.getCurrentUserId();
        userService.withdraw(requesterId, userId);
    }

    /** [어드민] 계정 잠금 상태 변경 */
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{userId}/locked")
    public UserResponse updateLocked(@PathVariable UUID userId, @Valid @RequestBody UserLockedUpdateRequest request) {
        return userService.updateLocked(userId, request.locked());
    }

    /** [어드민] 권한 수정 */
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{userId}/role")
    public UserResponse updateRole(@PathVariable UUID userId, @Valid @RequestBody UserRoleUpdateRequest request) {
        return userService.updateRole(userId, request.role());
    }
}