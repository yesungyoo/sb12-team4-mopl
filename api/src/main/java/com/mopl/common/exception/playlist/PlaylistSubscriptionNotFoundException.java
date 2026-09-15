package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistSubscriptionNotFoundException extends MoplException {
	public PlaylistSubscriptionNotFoundException() {
		super(PlaylistErrorCode.PLAYLIST_SUBSCRIPTION_NOT_FOUND);
	}
}