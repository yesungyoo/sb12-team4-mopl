package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistAccessDeniedException extends MoplException {

	public PlaylistAccessDeniedException() {
		super(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED);
	}
}