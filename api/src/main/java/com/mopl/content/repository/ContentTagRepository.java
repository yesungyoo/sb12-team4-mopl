package com.mopl.content.repository;

import com.mopl.core.domain.content.entity.ContentTag;
import com.mopl.core.domain.content.id.ContentTagId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContentTagRepository extends JpaRepository<ContentTag, ContentTagId> {

    // 단건 Content 색인 시 태그를 함께 조회
    @Query("""
            select ct
            from ContentTag ct
            join fetch ct.content c
            where c.id = :contentId
            """)
    List<ContentTag> findAllByContentId(@Param("contentId") UUID contentId);

    // 전체 재색인 시 콘텐츠 페이지 단위로 태그 일괄 조회
    @Query("""
            select ct
            from ContentTag ct
            join fetch ct.content c
            where c.id in :contentIds
            """)
    List<ContentTag> findAllByContentIds(@Param("contentIds") Collection<UUID> contentIds);
}
