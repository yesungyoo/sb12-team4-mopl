package com.mopl.playlist.controller;

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
		@RequestParam UUID requesterId, // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
		@PathVariable UUID playlistId
	) {
		playlistSubscriptionService.subscribe(requesterId, playlistId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping
	public ResponseEntity<Void> unsubscribe(
		@RequestParam UUID requesterId, // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
		@PathVariable UUID playlistId
	) {
		playlistSubscriptionService.unsubscribe(requesterId, playlistId);
		return ResponseEntity.noContent().build();
	}
}