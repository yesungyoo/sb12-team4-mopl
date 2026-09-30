package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistAiSessionNotFoundException extends MoplException {

	public PlaylistAiSessionNotFoundException() {
		super(PlaylistErrorCode.PLAYLIST_AI_SESSION_NOT_FOUND);
	}
}