package com.mopl.common.exception.playlist;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PlaylistErrorCode implements ErrorCode {

	PLAYLIST_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"PLAYLIST_001",
		"플레이리스트를 찾을 수 없습니다."
	),
	PLAYLIST_ACCESS_DENIED(
		HttpStatus.FORBIDDEN,
		"PLAYLIST_002",
		"플레이리스트에 대한 권한이 없습니다."
	),
	PLAYLIST_CONTENT_ALREADY_EXISTS(
		HttpStatus.BAD_REQUEST,
		"PLAYLIST_003",
		"이미 추가된 콘텐츠입니다."
	),
	PLAYLIST_CONTENT_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"PLAYLIST_004",
		"플레이리스트에 해당 콘텐츠가 없습니다."
	),
	PLAYLIST_SUBSCRIPTION_ALREADY_EXISTS(
		HttpStatus.BAD_REQUEST,
		"PLAYLIST_005",
		"이미 구독한 플레이리스트입니다."
	),
	PLAYLIST_SUBSCRIPTION_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"PLAYLIST_006",
		"구독하지 않은 플레이리스트입니다."
	);

	private final HttpStatus status;
	private final String code;
	private final String message;
}