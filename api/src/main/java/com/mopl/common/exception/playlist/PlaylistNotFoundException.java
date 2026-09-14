package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistNotFoundException extends MoplException {

	public PlaylistNotFoundException() {
		super(PlaylistErrorCode.PLAYLIST_NOT_FOUND);
	}
}