package com.mopl.review.repository;

import com.mopl.core.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Review> findById(UUID id);

    @EntityGraph(attributePaths = "user")
    Page<Review> findAllByContentId(UUID contentId, Pageable pageable);

    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);
}
