package com.mopl.user.service;

import com.mopl.auth.redis.TokenRedisService;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.common.event.RoleChangedEvent;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.CursorPageResponse;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserResponse;
import com.mopl.user.dto.UserSearchCondition;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.repository.UserSpecification;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final TokenRedisService tokenRedisService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    // ===================== 회원가입 =====================

    @Transactional
    public UserResponse signUp(UserCreateRequest request) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new MoplException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
        String encodedPassword = passwordEncoder.encode(request.password());
        User user = new User(request.email(), encodedPassword, request.name(), null, UserRole.USER);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 중복 체크와 저장 사이 짧은 순간에 동시 요청이 들어와 DB UNIQUE 제약(uk_users_email)에 걸린 경우.
            // 원인 모를 500 대신 동일한 의미의 400으로 변환해 응답한다.
            throw new MoplException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return UserResponse.from(user);
    }

    // ===================== 조회 =====================

    public UserResponse getUser(UUID userId) {
        User user = findActiveUserOrThrow(userId);
        return UserResponse.from(user);
    }

    public CursorPageResponse<UserResponse> getUsers(UserSearchCondition condition) {
        String sortField = UserSpecification.validateSortField(condition.sortBy());
        validatePagination(condition);
        UUID idAfter = parseUuidOrThrow(condition.idAfter());

        Specification<User> spec = Specification.allOf(
                UserSpecification.notDeleted(),
                UserSpecification.emailLike(condition.emailLike()),
                UserSpecification.roleEqual(condition.roleEqual()),
                UserSpecification.isLocked(condition.isLocked()),
                UserSpecification.cursorAfter(sortField, condition.sortDirection(), condition.cursor(), idAfter)
        );

        Sort.Direction direction = condition.sortDirection() == UserSearchCondition.SortDirection.ASCENDING
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortField).and(Sort.by(Sort.Direction.ASC, "id"));

        // hasNext 판단을 위해 limit + 1 로 조회
        int limit = condition.limit();
        List<User> fetched = userRepository.findAll(spec, PageRequest.of(0, limit + 1, sort)).getContent();

        boolean hasNext = fetched.size() > limit;
        List<User> pageContent = hasNext ? fetched.subList(0, limit) : fetched;

        List<UserResponse> data = pageContent.stream().map(UserResponse::from).toList();

        String nextCursor = null;
        String nextIdAfter = null;
        if (hasNext && !pageContent.isEmpty()) {
            User last = pageContent.getLast();
            nextCursor = extractCursorValue(last, sortField);
            nextIdAfter = last.getId().toString();
        }

        long totalCount = userRepository.count(Specification.allOf(
                UserSpecification.notDeleted(),
                UserSpecification.emailLike(condition.emailLike()),
                UserSpecification.roleEqual(condition.roleEqual()),
                UserSpecification.isLocked(condition.isLocked())
        ));

        return new CursorPageResponse<>(
                data, nextCursor, nextIdAfter, hasNext, totalCount,
                condition.sortBy(), condition.sortDirection().name()
        );
    }

    /** limit>0 검증, cursor/idAfter 는 둘 다 있거나 둘 다 없어야 함 (동점자 처리를 위해 짝으로 다녀야 함) */
    private void validatePagination(UserSearchCondition condition) {
        if (condition.limit() <= 0) {
            throw new MoplException(UserErrorCode.INVALID_PAGINATION_PARAM, "limit 은 0보다 커야 합니다. limit=" + condition.limit());
        }
        boolean hasCursor = condition.cursor() != null && !condition.cursor().isBlank();
        boolean hasIdAfter = condition.idAfter() != null && !condition.idAfter().isBlank();
        if (hasCursor != hasIdAfter) {
            throw new MoplException(UserErrorCode.INVALID_PAGINATION_PARAM, "cursor 와 idAfter 는 함께 전달되어야 합니다.");
        }
    }

    private UUID parseUuidOrThrow(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MoplException(UserErrorCode.INVALID_PAGINATION_PARAM, "idAfter 가 올바른 UUID 형식이 아닙니다. idAfter=" + value);
        }
    }

    private String extractCursorValue(User user, String entityField) {
        return switch (entityField) {
            case "name" -> user.getName();
            case "email" -> user.getEmail();
            case "createdAt" -> user.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            case "locked" -> String.valueOf(user.isLocked());
            case "role" -> user.getRole().name();
            default -> throw new IllegalStateException("unreachable: validated entityField=" + entityField);
        };
    }

    // ===================== 프로필 변경 (본인만) =====================

    @Transactional
    public UserResponse updateProfile(UUID requesterId, UUID targetUserId, String name, String profileImageUrl) {
        if (!requesterId.equals(targetUserId)) {
            throw new MoplException(UserErrorCode.ACCESS_DENIED);
        }
        findActiveUserOrThrow(targetUserId); // 존재/탈퇴 여부 확인
        userRepository.updateProfile(targetUserId, name, profileImageUrl);
        return UserResponse.from(findActiveUserOrThrow(targetUserId));
    }

    // ===================== 탈퇴 정책 =====================

    /** 본인 탈퇴. Soft Delete 처리하고, 이후 로그인/조회 대상에서 제외됨. */
    @Transactional
    public void withdraw(UUID requesterId, UUID targetUserId) {
        if (!requesterId.equals(targetUserId)) {
            throw new MoplException(UserErrorCode.ACCESS_DENIED);
        }
        findActiveUserOrThrow(targetUserId); // 존재/탈퇴 여부 확인
        userRepository.updateDeletedAt(targetUserId, LocalDateTime.now());
        tokenRedisService.deleteRefreshToken(targetUserId);
        tokenRedisService.invalidateTokensIssuedBefore(targetUserId, Instant.now());
    }

    // ===================== 잠금 정책 (어드민) =====================

    @Transactional
    public UserResponse updateLocked(UUID targetUserId, boolean locked) {
        findActiveUserOrThrow(targetUserId); // 존재/탈퇴 여부 확인
        userRepository.updateLocked(targetUserId, locked);
        if (locked) {
            tokenRedisService.deleteRefreshToken(targetUserId);
            tokenRedisService.invalidateTokensIssuedBefore(targetUserId, Instant.now());
        }
        return UserResponse.from(findActiveUserOrThrow(targetUserId));
    }

    // ===================== 권한 관리 (어드민) =====================

    @Transactional
    public UserResponse updateRole(UUID targetUserId, UserRole role) {
        findActiveUserOrThrow(targetUserId); // 존재/탈퇴 여부 확인
        userRepository.updateRole(targetUserId, role);
        tokenRedisService.deleteRefreshToken(targetUserId);
        tokenRedisService.invalidateTokensIssuedBefore(targetUserId, Instant.now());
        eventPublisher.publishEvent(new RoleChangedEvent(targetUserId, role.name()));
        return UserResponse.from(findActiveUserOrThrow(targetUserId));
    }

    // ===================== 공통 =====================

    private User findActiveUserOrThrow(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new MoplException(UserErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다. userId=" + userId));
    }
}