package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.RetryAfterProvider;
import lombok.Getter;

@Getter
public class PlaylistAiRateLimitExceededException
	extends MoplException
	implements RetryAfterProvider {

	private final long retryAfterSeconds;

	public PlaylistAiRateLimitExceededException(long retryAfterSeconds) {
		super(PlaylistErrorCode.PLAYLIST_AI_RATE_LIMIT_EXCEEDED);
		this.retryAfterSeconds = retryAfterSeconds;
	}
}