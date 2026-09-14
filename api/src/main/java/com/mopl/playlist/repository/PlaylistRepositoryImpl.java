package com.mopl.playlist.repository;

import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.core.domain.playlist.entity.QPlaylist;
import com.mopl.playlist.dto.PlaylistSortBy;
import com.mopl.playlist.dto.SortDirection;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.NumberExpression;
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

	public PlaylistRepositoryImpl(JPAQueryFactory queryFactory) {
		this.queryFactory = queryFactory;
	}

	@Override
	public List<Playlist> findAllByCursor(
		String cursor,
		UUID idAfter,
		int limitPlusOne,
		PlaylistSortBy sortBy,
		SortDirection sortDirection
	) {
		boolean isDesc = sortDirection == SortDirection.DESCENDING;

		BooleanBuilder condition = new BooleanBuilder();

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

	// subscriberCount는 아직 컬럼이 없어 항상 0 취급 -> id 기준으로만 정렬/커서 처리
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

	private OrderSpecifier<?>[] buildOrderSpecifiers(PlaylistSortBy sortBy, boolean isDesc) {
		if (sortBy == PlaylistSortBy.UPDATED_AT) {
			return new OrderSpecifier[]{
				isDesc ? playlist.updatedAt.desc() : playlist.updatedAt.asc(),
				isDesc ? playlist.id.desc() : playlist.id.asc()
			};
		}
		// SUBSCRIBE_COUNT: 집계 컬럼이 없어 현재는 id로만 정렬 (후속 이슈에서 실제 컬럼/집계 추가 시 교체)
		return new OrderSpecifier[]{
			isDesc ? playlist.id.desc() : playlist.id.asc()
		};
	}
}