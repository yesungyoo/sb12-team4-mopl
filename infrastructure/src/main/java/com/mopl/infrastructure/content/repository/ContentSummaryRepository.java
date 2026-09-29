package com.mopl.infrastructure.content.repository;

import com.mopl.core.domain.content.entity.ContentTag;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ContentSummaryRepository {

	private final EntityManager entityManager;

	public ContentSummaryQueryResult findByContentId(UUID contentId) {
		List<String> tags = entityManager.createQuery("""
                        select ct
                        from ContentTag ct
                        where ct.content.id = :contentId
                        """, ContentTag.class)
			.setParameter("contentId", contentId)
			.getResultList()
			.stream()
			.sorted(Comparator.comparing(ContentTag::getTag)
				.thenComparing(ContentTag::getValue))
			.map(tag -> tag.getTag() + "=" + tag.getValue())
			.toList();

		Object[] reviewSummary = entityManager.createQuery("""
                        select avg(r.rating), count(r)
                        from Review r
                        where r.content.id = :contentId
                        """, Object[].class)
			.setParameter("contentId", contentId)
			.getSingleResult();

		double averageRating = reviewSummary[0] == null
			? 0.0
			: ((Number) reviewSummary[0]).doubleValue();

		int reviewCount = Math.toIntExact(
			((Number) reviewSummary[1]).longValue()
		);

		return new ContentSummaryQueryResult(
			tags,
			averageRating,
			reviewCount
		);
	}
}