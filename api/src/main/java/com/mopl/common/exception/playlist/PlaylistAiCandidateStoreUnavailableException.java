package com.mopl.common.exception.playlist;

import com.mopl.common.exception.MoplException;

public class PlaylistAiCandidateStoreUnavailableException extends MoplException {

	public PlaylistAiCandidateStoreUnavailableException() {
		super(PlaylistErrorCode.PLAYLIST_AI_CANDIDATE_STORE_UNAVAILABLE);
	}
}