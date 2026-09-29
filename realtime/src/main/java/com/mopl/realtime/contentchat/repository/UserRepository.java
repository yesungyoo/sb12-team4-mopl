package com.mopl.realtime.contentchat.repository;

import com.mopl.core.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByIdAndDeletedAtIsNull(UUID id);
}