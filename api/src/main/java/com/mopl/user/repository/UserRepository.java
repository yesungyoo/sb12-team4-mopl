package com.mopl.user.repository;

import com.mopl.core.common.enums.UserRole;
import com.mopl.core.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * User 엔티티에 상태변경 메서드(lock/withdraw/changeRole 등)가 아직 없어서
 * (팀원과 협의 후 추후 엔티티에 추가 예정), 그 전까지는 벌크 업데이트 쿼리로 우회합니다.
 * User 는 BaseEntity 만 상속(= updatedAt 없음)이라 @Modifying 벌크쿼리로 영속성 컨텍스트를 우회해도
 * 놓치는 auditing 필드가 없습니다.
 */
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /** 로그인 시 사용. 탈퇴(soft delete)한 계정은 조회되지 않음. */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.locked = :locked where u.id = :id")
    void updateLocked(@Param("id") UUID id, @Param("locked") boolean locked);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.role = :role where u.id = :id")
    void updateRole(@Param("id") UUID id, @Param("role") UserRole role);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.deletedAt = :deletedAt where u.id = :id")
    void updateDeletedAt(@Param("id") UUID id, @Param("deletedAt") LocalDateTime deletedAt);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.name = :name, u.profileImageUrl = COALESCE(:profileImageUrl, u.profileImageUrl) where u.id = :id")
    void updateProfile(@Param("id") UUID id, @Param("name") String name, @Param("profileImageUrl") String profileImageUrl);
}
