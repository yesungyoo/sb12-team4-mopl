package com.mopl.playlist.ai.repository;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.core.domain.playlist.entity.PlaylistAiMessage;
import com.mopl.core.domain.playlist.entity.QPlaylistAiMessage;
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
public class PlaylistAiMessageRepositoryImpl
	implements PlaylistAiMessageRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	private static final QPlaylistAiMessage playlistAiMessage =
		QPlaylistAiMessage.playlistAiMessage;

	@Override
	public List<PlaylistAiMessage> findBySessionCursor(
		UUID sessionId,
		String cursor,
		UUID idAfter,
		int limit,
		boolean ascending
	) {
		BooleanBuilder where = new BooleanBuilder()
			.and(playlistAiMessage.session.id.eq(sessionId))
			.and(cursorCondition(cursor, idAfter, ascending));

		OrderSpecifier<?> primaryOrder = ascending
			? playlistAiMessage.createdAt.asc()
			: playlistAiMessage.createdAt.desc();

		OrderSpecifier<?> secondaryOrder = ascending
			? playlistAiMessage.id.asc()
			: playlistAiMessage.id.desc();

		return queryFactory
			.selectFrom(playlistAiMessage)
			.where(where)
			.orderBy(primaryOrder, secondaryOrder)
			.limit(limit)
			.fetch();
	}

	@Override
	public long countBySession(UUID sessionId) {
		Long count = queryFactory
			.select(playlistAiMessage.count())
			.from(playlistAiMessage)
			.where(playlistAiMessage.session.id.eq(sessionId))
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
			? playlistAiMessage.createdAt.gt(cursorTime)
			.or(
				playlistAiMessage.createdAt.eq(cursorTime)
					.and(playlistAiMessage.id.gt(idAfter))
			)
			: playlistAiMessage.createdAt.lt(cursorTime)
			.or(
				playlistAiMessage.createdAt.eq(cursorTime)
					.and(playlistAiMessage.id.lt(idAfter))
			);
	}
}