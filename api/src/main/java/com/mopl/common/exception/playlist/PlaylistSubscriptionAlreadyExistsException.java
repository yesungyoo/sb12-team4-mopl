package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistSubscriptionAlreadyExistsException extends MoplException {
	public PlaylistSubscriptionAlreadyExistsException() {
		super(PlaylistErrorCode.PLAYLIST_SUBSCRIPTION_ALREADY_EXISTS);
	}
}