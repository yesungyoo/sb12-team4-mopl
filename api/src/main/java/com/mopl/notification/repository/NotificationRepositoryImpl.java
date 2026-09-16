package com.mopl.notification.repository;

import com.mopl.core.domain.notification.entity.Notification;
import com.mopl.core.domain.notification.entity.QNotification;
import com.mopl.core.domain.user.entity.User;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<Notification> findByReceiverWithCursor(
      User receiver, UUID idAfter, LocalDateTime cursor, int size, String sortDirection
  ) {
    QNotification notification = QNotification.notification;
    boolean isAscending = "ASCENDING".equalsIgnoreCase(sortDirection);

    BooleanBuilder builder = new BooleanBuilder();
    builder.and(notification.receiver.eq(receiver));
    builder.and(notification.read.isFalse());

    if (idAfter != null && cursor != null) {
      if (isAscending) {
        // ASC: 커서보다 "이후" 데이터
        builder.and(
            notification.createdAt.gt(cursor)
                .or(notification.createdAt.eq(cursor)
                    .and(notification.id.gt(idAfter)))
        );
      } else {
        // DESC: 커서보다 "이전" 데이터
        builder.and(
            notification.createdAt.lt(cursor)
                .or(notification.createdAt.eq(cursor)
                    .and(notification.id.lt(idAfter)))
        );
      }
    }

    OrderSpecifier<?> createdAtOrder = isAscending
        ? notification.createdAt.asc()
        : notification.createdAt.desc();
    OrderSpecifier<?> idOrder = isAscending
        ? notification.id.asc()
        : notification.id.desc();

    return queryFactory
        .selectFrom(notification)
        .where(builder)
        .orderBy(createdAtOrder, idOrder)
        .limit(size)
        .fetch();
  }

  @Override
  public long countByReceiver(User receiver) {
    QNotification notification = QNotification.notification;
    Long count = queryFactory
        .select(notification.count())
        .from(notification)
        .where(
            notification.receiver.eq(receiver),
            notification.read.isFalse()
        )
        .fetchOne();
    return count != null ? count : 0L;
  }
}