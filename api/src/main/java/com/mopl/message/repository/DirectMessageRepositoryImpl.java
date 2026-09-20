package com.mopl.message.repository;

import com.mopl.core.domain.message.entity.DirectMessage;
import com.mopl.core.domain.message.entity.QDirectMessage;
import com.mopl.message.dto.SortDirection;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public class DirectMessageRepositoryImpl implements DirectMessageRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	private static final QDirectMessage directMessage = QDirectMessage.directMessage;

	@Override
	public List<DirectMessage> findByConversationCursor(
		UUID conversationId,
		String cursor,
		UUID idAfter,
		int limit,
		SortDirection sortDirection
	) {
		BooleanBuilder where = new BooleanBuilder()
			.and(directMessage.conversation.id.eq(conversationId))
			.and(cursorCondition(cursor, idAfter, sortDirection));

		boolean asc = sortDirection == SortDirection.ASCENDING;
		OrderSpecifier<?> primaryOrder = asc ? directMessage.createdAt.asc() : directMessage.createdAt.desc();
		OrderSpecifier<?> secondaryOrder = asc ? directMessage.id.asc() : directMessage.id.desc();

		return queryFactory
			.selectFrom(directMessage)
			.where(where)
			.orderBy(primaryOrder, secondaryOrder)
			.limit(limit)
			.fetch();
	}

	@Override
	public long countByConversation(UUID conversationId) {
		Long count = queryFactory
			.select(directMessage.count())
			.from(directMessage)
			.where(directMessage.conversation.id.eq(conversationId))
			.fetchOne();

		return count == null ? 0L : count;
	}

	private BooleanExpression cursorCondition(String cursor, UUID idAfter, SortDirection sortDirection) {
		if (cursor == null || idAfter == null) {
			return null;
		}

		LocalDateTime cursorTime = LocalDateTime.parse(cursor);
		boolean asc = sortDirection == SortDirection.ASCENDING;

		return asc
			? directMessage.createdAt.gt(cursorTime)
			.or(directMessage.createdAt.eq(cursorTime).and(directMessage.id.gt(idAfter)))
			: directMessage.createdAt.lt(cursorTime)
			.or(directMessage.createdAt.eq(cursorTime).and(directMessage.id.lt(idAfter)));
	}
}