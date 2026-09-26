package com.mopl.content.repository;

import com.mopl.core.domain.content.entity.ContentView;
import com.mopl.core.domain.content.id.ContentViewId;
import com.mopl.content.repository.projection.ContentViewStatisticsProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContentViewRepository extends JpaRepository<ContentView, ContentViewId> {

    // watchCount = 콘텐츠를 조회한 고유 사용자 수
    long countByContent_Id(UUID contentId);

    // 전체 재색인 시 콘텐츠별 watcherCount 일괄 조회
    @Query("""
            select
                cv.content.id as contentId,
                count(cv) as watcherCount
            from ContentView cv
            where cv.content.id in :contentIds
            group by cv.content.id
            """)
    List<ContentViewStatisticsProjection> findStatisticsByContentIds(
            @Param("contentIds") Collection<UUID> contentIds
    );
}
