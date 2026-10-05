package com.mopl.realtime.contentchat.repository;

import com.mopl.core.domain.user.entity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByIdAndDeletedAtIsNull(UUID id);

	@Query("select f.follower.id from Follow f where f.followee.id = :followeeId")
	List<UUID> findFollowerIds(@Param("followeeId") UUID followeeId);
}
