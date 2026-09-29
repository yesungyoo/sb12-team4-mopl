package com.mopl.watchingsession.controller;

import com.mopl.core.common.dto.CursorResponse;
import com.mopl.watchingsession.dto.WatchingSessionResponse;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WatchingSessionController {

	private final WatchingSessionService watchingSessionService;

	@GetMapping("/users/{watcherId}/watching-sessions")
	public ResponseEntity<WatchingSessionResponse> getWatchingSession(
		@PathVariable UUID watcherId
	) {
		WatchingSessionResponse response =
			watchingSessionService.getWatchingSession(watcherId);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/contents/{contentId}/watching-sessions")
	public ResponseEntity<CursorResponse<WatchingSessionResponse>> getWatchingSessions(
		@PathVariable UUID contentId,
		@RequestParam(required = false) String watcherNameLike,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) UUID idAfter,
		@RequestParam int limit,
		@RequestParam String sortBy,
		@RequestParam String sortDirection
	) {
		CursorResponse<WatchingSessionResponse> response =
			watchingSessionService.getWatchingSessions(
				contentId,
				watcherNameLike,
				cursor,
				idAfter,
				limit,
				sortBy,
				sortDirection
			);

		return ResponseEntity.ok(response);
	}
}