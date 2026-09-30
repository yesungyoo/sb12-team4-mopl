package com.mopl.playlist.ai.repository;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.core.domain.playlist.entity.PlaylistAiSession;
import com.mopl.core.domain.playlist.entity.QPlaylistAiSession;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public class PlaylistAiSessionRepositoryImpl
	implements PlaylistAiSessionRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	private static final QPlaylistAiSession playlistAiSession =
		QPlaylistAiSession.playlistAiSession;

	@Override
	public List<PlaylistAiSession> findByUserCursor(
		UUID userId,
		String cursor,
		UUID idAfter,
		int limit,
		boolean ascending
	) {
		BooleanBuilder where = new BooleanBuilder()
			.and(playlistAiSession.user.id.eq(userId))
			.and(cursorCondition(cursor, idAfter, ascending));

		OrderSpecifier<?> primaryOrder = ascending
			? playlistAiSession.updatedAt.asc()
			: playlistAiSession.updatedAt.desc();

		OrderSpecifier<?> secondaryOrder = ascending
			? playlistAiSession.id.asc()
			: playlistAiSession.id.desc();

		return queryFactory
			.selectFrom(playlistAiSession)
			.where(where)
			.orderBy(primaryOrder, secondaryOrder)
			.limit(limit)
			.fetch();
	}

	@Override
	public long countByUser(UUID userId) {
		Long count = queryFactory
			.select(playlistAiSession.count())
			.from(playlistAiSession)
			.where(playlistAiSession.user.id.eq(userId))
			.fetchOne();

		return count == null ? 0L : count;
	}

	private BooleanExpression cursorCondition(
		String cursor,
		UUID idAfter,
		boolean ascending
	) {
		if (cursor == null || idAfter == null) {
			return null;
		}

		LocalDateTime cursorTime;

		try {
			cursorTime = LocalDateTime.parse(cursor);
		} catch (DateTimeParseException e) {
			throw new MoplException(
				CommonErrorCode.INVALID_INPUT_VALUE
			);
		}

		return ascending
			? playlistAiSession.updatedAt.gt(cursorTime)
			.or(
				playlistAiSession.updatedAt.eq(cursorTime)
					.and(playlistAiSession.id.gt(idAfter))
			)
			: playlistAiSession.updatedAt.lt(cursorTime)
			.or(
				playlistAiSession.updatedAt.eq(cursorTime)
					.and(playlistAiSession.id.lt(idAfter))
			);
	}
}