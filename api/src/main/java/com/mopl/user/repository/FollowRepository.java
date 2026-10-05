package com.mopl.user.repository;

import com.mopl.core.domain.user.entity.Follow;
import com.mopl.core.domain.user.entity.User;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    void deleteByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long countByFolloweeId(UUID followeeId);

    long countByFollowerId(UUID followerId);

    /** userId 를 팔로우하는 사람들(팔로워) 목록 */
    @Query("select f.follower from Follow f where f.followee.id = :followeeId order by f.createdAt desc")
    Page<User> findFollowers(@Param("followeeId") UUID followeeId, Pageable pageable);

    @Query("select f.follower.id from Follow f where f.followee.id = :followeeId")
    List<UUID> findFollowerIds(@Param("followeeId") UUID followeeId);

    /** userId 가 팔로우하는 사람들(팔로잉) 목록 */
    @Query("select f.followee from Follow f where f.follower.id = :followerId order by f.createdAt desc")
    Page<User> findFollowing(@Param("followerId") UUID followerId, Pageable pageable);
}
