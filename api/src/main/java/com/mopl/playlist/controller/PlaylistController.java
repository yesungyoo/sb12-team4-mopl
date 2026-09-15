package com.mopl.playlist.controller;

import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistListResponse;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/playlists")
public class PlaylistController {

	private final PlaylistService playlistService;

	public PlaylistController(PlaylistService playlistService) {
		this.playlistService = playlistService;
	}

	@GetMapping("/{playlistId}")
	public ResponseEntity<PlaylistResponse> getPlaylist(
		@PathVariable UUID playlistId,
		@RequestParam(required = false) UUID requesterId // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
	) {
		PlaylistResponse response = playlistService.getPlaylist(playlistId, requesterId);

		return ResponseEntity.ok(response);
	}

	@GetMapping
	public ResponseEntity<PlaylistListResponse> getPlaylists(
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) UUID idAfter,
		@RequestParam int limit,
		@RequestParam String sortBy,
		@RequestParam String sortDirection,
		@RequestParam(required = false) UUID requesterId, // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
		@RequestParam(required = false) UUID subscriberIdEqual
	) {
		PlaylistListResponse response = playlistService.getPlaylists(
			cursor, idAfter, limit, sortBy, sortDirection, requesterId, subscriberIdEqual
		);

		return ResponseEntity.ok(response);
	}

	@PostMapping
	public ResponseEntity<PlaylistResponse> createPlaylist(
		@RequestParam UUID requesterId, // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
		@Valid @RequestBody PlaylistCreateRequest request
	) {
		PlaylistResponse response = playlistService.createPlaylist(requesterId, request);

		URI location = URI.create("/playlists/" + response.id());

		return ResponseEntity
			.created(location)
			.body(response);
	}

	@PatchMapping("/{playlistId}")
	public ResponseEntity<PlaylistResponse> updatePlaylist(
		@RequestParam UUID requesterId, // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
		@PathVariable UUID playlistId,
		@Valid @RequestBody PlaylistUpdateRequest request
	) {
		PlaylistResponse response = playlistService.updatePlaylist(requesterId, playlistId, request);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{playlistId}")
	public ResponseEntity<Void> deletePlaylist(
		@RequestParam UUID requesterId, // TODO: Security 완료 후 @AuthenticationPrincipal로 교체
		@PathVariable UUID playlistId
	) {
		playlistService.deletePlaylist(requesterId, playlistId);

		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{playlistId}/contents/{contentId}")
	public ResponseEntity<Void> addContentToPlaylist(
		@RequestParam UUID requesterId,
		@PathVariable UUID playlistId,
		@PathVariable UUID contentId
	) {
		playlistService.addContentToPlaylist(requesterId, playlistId, contentId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{playlistId}/contents/{contentId}")
	public ResponseEntity<Void> removeContentFromPlaylist(
		@RequestParam UUID requesterId,
		@PathVariable UUID playlistId,
		@PathVariable UUID contentId
	) {
		playlistService.removeContentFromPlaylist(requesterId, playlistId, contentId);
		return ResponseEntity.noContent().build();
	}
}