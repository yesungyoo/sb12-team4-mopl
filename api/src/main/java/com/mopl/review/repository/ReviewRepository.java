package com.mopl.review.repository;

import com.mopl.core.domain.review.entity.Review;
import com.mopl.review.repository.projection.ContentReviewStatisticsProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Review> findById(UUID id);

    @EntityGraph(attributePaths = "user")
    Page<Review> findAllByContentId(UUID contentId, Pageable pageable);

    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);

    // 단일 콘텐츠 평균 평점 조회
    @Query("""
            select avg(r.rating)
            from Review r
            where r.content.id = :contentId
            """)
    Double findAverageRatingByContentId(@Param("contentId") UUID contentId);

    // 단일 콘텐츠 리뷰 수 조회
    long countByContentId(UUID contentId);

    // 전체 재색인 시 콘텐츠별 리뷰 통계 일괄 조회
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

    // 개인화 추천용 고평점 리뷰 조회
    @Query("""
            select r
            from Review r
            join fetch r.content c
            where r.user.id = :userId
                and c.deletedAt is null
                and r.rating >= :minRating
            order by r.rating desc, r.updatedAt desc
            """)
    List<Review> findHighRatedByUserId(@Param("userId") UUID userId,
                                       @Param("minRating") BigDecimal minRating,
                                       Pageable pageable);
}
