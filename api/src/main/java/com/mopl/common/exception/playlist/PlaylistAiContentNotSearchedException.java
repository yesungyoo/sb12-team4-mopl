package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistAiContentNotSearchedException extends MoplException {

	public PlaylistAiContentNotSearchedException() {
		super(PlaylistErrorCode.PLAYLIST_AI_CONTENT_NOT_SEARCHED);
	}
}