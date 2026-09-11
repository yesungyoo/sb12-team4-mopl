package com.mopl.content.repository;

import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.QContent;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ContentRepositoryCustomImpl implements ContentRepositoryCustom {

    private static final QContent content = QContent.content;

    private final JPAQueryFactory queryFactory;

    public ContentRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<Content> search(ContentSearchCondition condition, Pageable pageable) {
        JPAQuery<Content> query = queryFactory
                .selectFrom(content)
                .where(
                        content.deletedAt.isNull(),
                        titleContains(condition.keyword()),
                        typeEq(condition.type()),
                        releaseDateGoe(condition.releaseDateFrom()),
                        releaseDateLoe(condition.releaseDateTo())
                );

        for (OrderSpecifier<?> orderSpecifier : getOrderSpecifiers(pageable)) {
            query.orderBy(orderSpecifier);
        }
        List<Content> contents = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(content.count())
                .from(content)
                .where(
                        content.deletedAt.isNull(),
                        titleContains(condition.keyword()),
                        typeEq(condition.type()),
                        releaseDateGoe(condition.releaseDateFrom()),
                        releaseDateLoe(condition.releaseDateTo())
                )
                .fetchOne();

        return new PageImpl<>(contents, pageable, total != null ? total : 0L);
    }

    private BooleanExpression titleContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }

        return content.title.containsIgnoreCase(keyword);
    }

    private BooleanExpression typeEq(ContentType type) {
        if (type == null) {
            return null;
        }

        return content.type.eq(type);
    }

    private BooleanExpression releaseDateGoe(LocalDate releaseDateFrom) {
        if (releaseDateFrom == null) {
            return null;
        }

        return content.releaseDate.goe(releaseDateFrom);
    }

    private BooleanExpression releaseDateLoe(LocalDate releaseDateTo) {
        if (releaseDateTo == null) {
            return null;
        }

        return content.releaseDate.loe(releaseDateTo);
    }

    private List<OrderSpecifier<?>> getOrderSpecifiers(Pageable pageable) {
        List<OrderSpecifier<?>> orderSpecifiers = new ArrayList<>();

        if (pageable.getSort().isUnsorted()) {
            orderSpecifiers.add(content.createdAt.desc());
            orderSpecifiers.add(content.id.asc());

            return orderSpecifiers;
        }

        for (Sort.Order order : pageable.getSort()) {
            OrderSpecifier<?> orderSpecifier = toOrderSpecifier(order);

            if (orderSpecifier != null) {
                orderSpecifiers.add(orderSpecifier);
            }
        }

        if (orderSpecifiers.isEmpty()) {
            orderSpecifiers.add(content.createdAt.desc());
        }

        orderSpecifiers.add(content.id.asc());

        return orderSpecifiers;
    }

    private OrderSpecifier<?> toOrderSpecifier(Sort.Order order) {
        boolean ascending = order.isAscending();

        return switch (order.getProperty()) {
            case "createdAt" -> ascending ? content.createdAt.asc() : content.createdAt.desc();
            case "releaseDate" -> ascending ? content.releaseDate.asc() : content.releaseDate.desc();
            case "title" -> ascending ? content.title.asc() : content.title.desc();
            case "externalPopularity"
                    -> ascending ? content.externalPopularity.asc() : content.externalPopularity.desc();
            case "externalRating" -> ascending ? content.externalRating.asc() : content.externalRating.desc();
            default -> null;
        };
    }
}
