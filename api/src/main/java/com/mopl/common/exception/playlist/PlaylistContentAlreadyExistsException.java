package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistContentAlreadyExistsException extends MoplException {
	public PlaylistContentAlreadyExistsException() {
		super(PlaylistErrorCode.PLAYLIST_CONTENT_ALREADY_EXISTS);
	}
}