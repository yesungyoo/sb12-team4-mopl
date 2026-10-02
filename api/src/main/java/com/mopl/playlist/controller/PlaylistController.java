package com.mopl.playlist.controller;

import com.mopl.auth.util.SecurityUtil;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistResponse;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

	private final PlaylistService playlistService;

	public PlaylistController(PlaylistService playlistService) {
		this.playlistService = playlistService;
	}

	@GetMapping("/{playlistId}")
	public ResponseEntity<PlaylistResponse> getPlaylist(
		@PathVariable UUID playlistId
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		PlaylistResponse response =
			playlistService.getPlaylist(playlistId, currentUserId);

		return ResponseEntity.ok(response);
	}

	@GetMapping
	public ResponseEntity<CursorResponse<PlaylistResponse>> getPlaylists(
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) UUID idAfter,
		@RequestParam int limit,
		@RequestParam String sortBy,
		@RequestParam String sortDirection,
		@RequestParam(required = false) UUID subscriberIdEqual,
		@RequestParam(required = false) UUID ownerIdEqual,
		@RequestParam(required = false) String keywordLike
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		CursorResponse<PlaylistResponse> response = playlistService.getPlaylists(
			cursor,
			idAfter,
			limit,
			sortBy,
			sortDirection,
			currentUserId,
			subscriberIdEqual,
			ownerIdEqual,
			keywordLike
		);

		return ResponseEntity.ok(response);
	}

	@PostMapping
	public ResponseEntity<PlaylistResponse> createPlaylist(
		@Valid @RequestBody PlaylistCreateRequest request
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		PlaylistResponse response =
			playlistService.createPlaylist(currentUserId, request);

		URI location = URI.create("/playlists/" + response.id());

		return ResponseEntity
			.created(location)
			.body(response);
	}

	@PatchMapping("/{playlistId}")
	public ResponseEntity<PlaylistResponse> updatePlaylist(
		@PathVariable UUID playlistId,
		@Valid @RequestBody PlaylistUpdateRequest request
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		PlaylistResponse response =
			playlistService.updatePlaylist(currentUserId, playlistId, request);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{playlistId}")
	public ResponseEntity<Void> deletePlaylist(
		@PathVariable UUID playlistId
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		playlistService.deletePlaylist(currentUserId, playlistId);

		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{playlistId}/contents/{contentId}")
	public ResponseEntity<Void> addContentToPlaylist(
		@PathVariable UUID playlistId,
		@PathVariable UUID contentId
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		playlistService.addContentToPlaylist(
			currentUserId,
			playlistId,
			contentId
		);

		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{playlistId}/contents/{contentId}")
	public ResponseEntity<Void> removeContentFromPlaylist(
		@PathVariable UUID playlistId,
		@PathVariable UUID contentId
	) {
		UUID currentUserId = SecurityUtil.getCurrentUserId();

		playlistService.removeContentFromPlaylist(
			currentUserId,
			playlistId,
			contentId
		);

		return ResponseEntity.noContent().build();
	}
}