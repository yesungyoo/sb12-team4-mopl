package com.mopl.playlist.repository;

import com.mopl.core.domain.playlist.entity.Playlist;
import com.mopl.playlist.dto.PlaylistSortBy;
import com.mopl.playlist.dto.SortDirection;

import java.util.List;
import java.util.UUID;

public interface PlaylistRepositoryCustom {

	List<Playlist> findAllByCursor(
		String cursor,
		UUID idAfter,
		int limitPlusOne,
		PlaylistSortBy sortBy,
		SortDirection sortDirection
	);
}