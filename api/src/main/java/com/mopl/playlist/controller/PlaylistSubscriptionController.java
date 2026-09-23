package com.mopl.playlist.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.playlist.service.PlaylistSubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/playlists/{playlistId}/subscription")
public class PlaylistSubscriptionController {

	private final PlaylistSubscriptionService playlistSubscriptionService;

	public PlaylistSubscriptionController(PlaylistSubscriptionService playlistSubscriptionService) {
		this.playlistSubscriptionService = playlistSubscriptionService;
	}

	@PostMapping
	public ResponseEntity<Void> subscribe(
		@PathVariable UUID playlistId
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		playlistSubscriptionService.subscribe(currentUserId, playlistId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping
	public ResponseEntity<Void> unsubscribe(
		@PathVariable UUID playlistId
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		playlistSubscriptionService.unsubscribe(currentUserId, playlistId);
		return ResponseEntity.noContent().build();
	}
}