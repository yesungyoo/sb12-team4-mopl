package com.mopl.review.repository;

import com.mopl.core.domain.review.entity.Review;
import com.mopl.review.repository.projection.ContentReviewStatisticsProjection;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID>, JpaSpecificationExecutor<Review> {

    @Override
    @EntityGraph(attributePaths = {"user", "content"})
    Optional<Review> findById(UUID id);

    @EntityGraph(attributePaths = {"user", "content"})
    Page<Review> findAllByContentId(UUID contentId, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"user", "content"})
    Page<Review> findAll(Specification<Review> spec, Pageable pageable);

    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);

    @Query("""
            select avg(r.rating)
            from Review r
            where r.content.id = :contentId
            """)
    Double findAverageRatingByContentId(@Param("contentId") UUID contentId);

    long countByContentId(UUID contentId);

    @Query("""
            select
            r.content.id as contentId,
            avg(r.rating) as averageRating,
            count(r.id) as reviewCount
            from Review r
            where r.content.id in :contentIds
            group by r.content.id
            """)
    List<ContentReviewStatisticsProjection> findStatisticsByContentIds(
            @Param("contentIds") Collection<UUID> contentIds
    );

    @Query("""
            select r
            from Review r
            join fetch r.content c
            where r.user.id = :userId
                and c.deletedAt is null
                and r.rating >= :minRating
            order by r.rating desc, r.updatedAt desc
            """)
    List<Review> findHighRatedByUserId(
            @Param("userId") UUID userId,
            @Param("minRating") BigDecimal minRating,
            Pageable pageable
    );
}
