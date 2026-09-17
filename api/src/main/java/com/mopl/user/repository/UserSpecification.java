package com.mopl.user.repository;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.User;
import com.mopl.user.dto.UserSearchCondition.SortDirection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public class UserSpecification {

    /**
     * Swagger sortBy 값(외부 노출명) -> 실제 User 엔티티 필드명 매핑.
     * isLocked 는 Swagger/쿼리 파라미터 관례상 이름이고, 엔티티 필드는 locked(boolean) 이라 이름이 다르다.
     * 화이트리스트 역할도 겸함 (임의 필드/SQL Injection 방지) - 여기 없는 값은 전부 400 처리.
     */
    private static final Map<String, String> SORT_FIELD_MAPPING = Map.of(
            "name", "name",
            "email", "email",
            "createdAt", "createdAt",
            "isLocked", "locked",
            "role", "role"
    );

    private UserSpecification() {
    }

    /**
     * sortBy 값 검증 후, 실제 엔티티 필드명으로 변환해서 반환한다.
     * 잘못된 값이면 400(USER_005).
     */
    public static String validateSortField(String sortBy) {
        String entityField = SORT_FIELD_MAPPING.get(sortBy);
        if (entityField == null) {
            throw new MoplException(UserErrorCode.INVALID_SORT_FIELD, "지원하지 않는 정렬 기준입니다. sortBy=" + sortBy);
        }
        return entityField;
    }

    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<User> emailLike(String emailLike) {
        if (emailLike == null || emailLike.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.like(root.get("email"), "%" + emailLike + "%");
    }

    public static Specification<User> roleEqual(UserRole role) {
        if (role == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<User> isLocked(Boolean locked) {
        if (locked == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("locked"), locked);
    }

    /**
     * 커서 기반 페이징 조건.
     * entityField 는 호출 전에 validateSortField() 로 검증/변환된 실제 엔티티 필드명이어야 함.
     */
    public static Specification<User> cursorAfter(
            String entityField, SortDirection direction, String cursor, UUID idAfter
    ) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        boolean ascending = direction == SortDirection.ASCENDING;

        return (root, query, cb) -> {
            Object cursorValue = parseCursorValue(entityField, cursor);
            Predicate strictAfter = compare(cb, root.get(entityField), cursorValue, ascending);

            if (idAfter == null) {
                return strictAfter;
            }

            Predicate sameValueTieBreak = cb.and(
                    cb.equal(root.get(entityField), cursorValue),
                    cb.greaterThan(root.get("id"), idAfter)
            );
            return cb.or(strictAfter, sameValueTieBreak);
        };
    }

    /**
     * CriteriaBuilder.greaterThan/lessThan 은 제네릭이 Y extends Comparable<? super Y> 로 엮여있어서
     * raw Comparable 로 직접 넘기면 타입 추론이 깨진다. 이 헬퍼 안에서만 unchecked 캐스팅해서 흡수한다.
     * (Boolean, UserRole 은 Comparable 을 구현하므로 name/email/createdAt 과 동일하게 처리 가능)
     */
    @SuppressWarnings("unchecked")
    private static <Y extends Comparable<? super Y>> Predicate compare(
            CriteriaBuilder cb, Path<?> path, Object value, boolean ascending
    ) {
        Path<Y> typedPath = (Path<Y>) path;
        Y typedValue = (Y) value;
        return ascending ? cb.greaterThan(typedPath, typedValue) : cb.lessThan(typedPath, typedValue);
    }

    /** entityField(검증된 실제 필드명) 에 맞는 타입으로 cursor 문자열을 파싱한다. */
    private static Comparable<?> parseCursorValue(String entityField, String cursor) {
        return switch (entityField) {
            case "createdAt" -> LocalDateTime.parse(cursor);
            case "locked" -> Boolean.parseBoolean(cursor);
            case "role" -> UserRole.valueOf(cursor);
            default -> cursor; // name, email
        };
    }
}