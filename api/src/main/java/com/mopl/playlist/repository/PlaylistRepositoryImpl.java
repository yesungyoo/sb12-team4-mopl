package com.mopl.playlist.repository;

import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.playlist.entity.QPlaylist;
import com.mopl.core.domain.playlist.entity.QPlaylistSubscription;
import com.mopl.playlist.dto.PlaylistSortBy;
import com.mopl.playlist.dto.SortDirection;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import java.time.format.DateTimeParseException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class PlaylistRepositoryImpl implements PlaylistRepositoryCustom {

	private final JPAQueryFactory queryFactory;
	private static final QPlaylist playlist = QPlaylist.playlist;
	private static final QPlaylistSubscription subscription = QPlaylistSubscription.playlistSubscription;

	public PlaylistRepositoryImpl(JPAQueryFactory queryFactory) {
		this.queryFactory = queryFactory;
	}

	// playlist_subscriptions를 COUNT한 서브쿼리 표현식
	private NumberExpression<Long> subscribeCountExpr() {
		return Expressions.asNumber(
			queryFactory
				.select(subscription.count())
				.from(subscription)
				.where(subscription.playlist.id.eq(playlist.id))
		);
	}

	// subscriberIdEqual이 주어졌을 때, 해당 사용자가 구독한 플레이리스트만 걸러내는 조건
	private BooleanExpression subscriberEqualCondition(UUID subscriberIdEqual) {
		return JPAExpressions
			.selectOne()
			.from(subscription)
			.where(
				subscription.playlist.id.eq(playlist.id)
					.and(subscription.subscriber.id.eq(subscriberIdEqual))
			)
			.exists();
	}

	// 검색/필터 공통 조건 조립 (subscriberIdEqual, ownerIdEqual, keywordLike)
	private BooleanBuilder buildSearchCondition(UUID subscriberIdEqual, UUID ownerIdEqual, String keywordLike) {
		BooleanBuilder builder = new BooleanBuilder();

		if (subscriberIdEqual != null) {
			builder.and(subscriberEqualCondition(subscriberIdEqual));
		}
		if (ownerIdEqual != null) {
			builder.and(playlist.owner.id.eq(ownerIdEqual));
		}
		if (keywordLike != null && !keywordLike.isBlank()) {
			builder.and(
				playlist.title.containsIgnoreCase(keywordLike)
					.or(playlist.description.containsIgnoreCase(keywordLike))
			);
		}

		return builder;
	}

	@Override
	public List<Playlist> findAllByCursor(
		String cursor,
		UUID idAfter,
		int limitPlusOne,
		PlaylistSortBy sortBy,
		SortDirection sortDirection,
		UUID subscriberIdEqual,
		UUID ownerIdEqual,
		String keywordLike
	) {
		boolean isDesc = sortDirection == SortDirection.DESCENDING;

		BooleanBuilder condition = buildSearchCondition(subscriberIdEqual, ownerIdEqual, keywordLike);

		if (cursor != null && idAfter != null) {
			condition.and(buildCursorCondition(sortBy, isDesc, cursor, idAfter));
		}

		return queryFactory
			.selectFrom(playlist)
			.join(playlist.owner).fetchJoin()   // 추가: owner를 미리 로딩해서 N+1 방지
			.where(condition)
			.orderBy(buildOrderSpecifiers(sortBy, isDesc))
			.limit(limitPlusOne)
			.fetch();
	}

	@Override
	public long countAllMatching(UUID subscriberIdEqual, UUID ownerIdEqual, String keywordLike) {
		BooleanBuilder condition = buildSearchCondition(subscriberIdEqual, ownerIdEqual, keywordLike);

		Long count = queryFactory
			.select(playlist.count())
			.from(playlist)
			.where(condition)
			.fetchOne();

		return count != null ? count : 0L;
	}

	private BooleanBuilder buildCursorCondition(PlaylistSortBy sortBy, boolean isDesc, String cursor, UUID idAfter) {
		BooleanBuilder builder = new BooleanBuilder();

		if (sortBy == PlaylistSortBy.UPDATED_AT) {
			LocalDateTime cursorValue = parseCursorAsDateTime(cursor);

			if (isDesc) {
				builder.or(playlist.updatedAt.lt(cursorValue));
				builder.or(playlist.updatedAt.eq(cursorValue).and(playlist.id.lt(idAfter)));
			} else {
				builder.or(playlist.updatedAt.gt(cursorValue));
				builder.or(playlist.updatedAt.eq(cursorValue).and(playlist.id.gt(idAfter)));
			}
		} else if (sortBy == PlaylistSortBy.SUBSCRIBE_COUNT) {
			long cursorValue = parseCursorAsLong(cursor);
			NumberExpression<Long> countExpr = subscribeCountExpr();

			if (isDesc) {
				builder.or(countExpr.lt(cursorValue));
				builder.or(countExpr.eq(cursorValue).and(playlist.id.lt(idAfter)));
			} else {
				builder.or(countExpr.gt(cursorValue));
				builder.or(countExpr.eq(cursorValue).and(playlist.id.gt(idAfter)));
			}
		} else {
			if (isDesc) {
				builder.or(playlist.id.lt(idAfter));
			} else {
				builder.or(playlist.id.gt(idAfter));
			}
		}

		return builder;
	}

	private LocalDateTime parseCursorAsDateTime(String cursor) {
		try {
			return LocalDateTime.parse(cursor);
		} catch (DateTimeParseException e) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
	}

	private long parseCursorAsLong(String cursor) {
		try {
			return Long.parseLong(cursor);
		} catch (NumberFormatException e) {
			throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
		}
	}

	private OrderSpecifier<?>[] buildOrderSpecifiers(PlaylistSortBy sortBy, boolean isDesc) {
		if (sortBy == PlaylistSortBy.UPDATED_AT) {
			return new OrderSpecifier[]{
				isDesc ? playlist.updatedAt.desc() : playlist.updatedAt.asc(),
				isDesc ? playlist.id.desc() : playlist.id.asc()
			};
		}
		if (sortBy == PlaylistSortBy.SUBSCRIBE_COUNT) {
			NumberExpression<Long> countExpr = subscribeCountExpr();
			return new OrderSpecifier[]{
				isDesc ? countExpr.desc() : countExpr.asc(),
				isDesc ? playlist.id.desc() : playlist.id.asc()
			};
		}
		return new OrderSpecifier[]{
			isDesc ? playlist.id.desc() : playlist.id.asc()
		};
	}
}