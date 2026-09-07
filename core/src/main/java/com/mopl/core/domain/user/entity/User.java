package com.mopl.core.domain.user.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.common.enums.UserRole;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private UUID id;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "temp_password", length = 255)
    private String tempPassword;

    @Column(name = "temp_password_expired_at")
    private LocalDateTime tempPasswordExpiredAt;

    protected User() {}

    public User(
            String email,
            String password,
            String name,
            String profileImageUrl,
            UserRole role
    ) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.profileImageUrl = profileImageUrl;
        this.role = role;
        this.locked = false;
    }

    public UUID getId() {
        return id;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public String getName() {
        return name;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isLocked() {
        return locked;
    }

    public String getTempPassword() {
        return tempPassword;
    }

    public LocalDateTime getTempPasswordExpiredAt() {
        return tempPasswordExpiredAt;
    }
}
