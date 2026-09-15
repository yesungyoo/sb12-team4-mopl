package com.mopl.playlist.repository;

import com.mopl.core.domain.playlist.entity.QPlaylistSubscription;
import com.querydsl.jpa.impl.JPAQueryFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlaylistSubscriptionRepositoryImpl implements PlaylistSubscriptionRepositoryCustom {

	private final JPAQueryFactory queryFactory;
	private static final QPlaylistSubscription subscription = QPlaylistSubscription.playlistSubscription;

	public PlaylistSubscriptionRepositoryImpl(JPAQueryFactory queryFactory) {
		this.queryFactory = queryFactory;
	}

	@Override
	public Map<UUID, Long> countByPlaylistIdIn(List<UUID> playlistIds) {
		if (playlistIds.isEmpty()) {
			return Map.of();
		}

		return queryFactory
			.select(subscription.playlist.id, subscription.count())
			.from(subscription)
			.where(subscription.playlist.id.in(playlistIds))
			.groupBy(subscription.playlist.id)
			.fetch()
			.stream()
			.collect(Collectors.toMap(
				tuple -> tuple.get(subscription.playlist.id),
				tuple -> tuple.get(subscription.count())
			));
	}

	@Override
	public Set<UUID> findSubscribedPlaylistIds(List<UUID> playlistIds, UUID subscriberId) {
		if (playlistIds.isEmpty()) {
			return Set.of();
		}

		return queryFactory
			.select(subscription.playlist.id)
			.from(subscription)
			.where(
				subscription.playlist.id.in(playlistIds),
				subscription.subscriber.id.eq(subscriberId)
			)
			.fetch()
			.stream()
			.collect(Collectors.toSet());
	}
}