package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistContentNotFoundException extends MoplException {
	public PlaylistContentNotFoundException() {
		super(PlaylistErrorCode.PLAYLIST_CONTENT_NOT_FOUND);
	}
}