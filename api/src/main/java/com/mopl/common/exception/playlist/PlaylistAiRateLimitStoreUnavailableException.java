package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistAiRateLimitStoreUnavailableException extends MoplException {

	public PlaylistAiRateLimitStoreUnavailableException() {
		super(PlaylistErrorCode.PLAYLIST_AI_RATE_LIMIT_STORE_UNAVAILABLE);
	}
}