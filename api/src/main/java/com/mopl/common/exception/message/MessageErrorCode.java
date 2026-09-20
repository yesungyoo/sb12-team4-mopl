package com.mopl.common.exception.message;

import com.mopl.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MessageErrorCode implements ErrorCode {

	CONVERSATION_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"MESSAGE_001",
		"대화를 찾을 수 없습니다."
	),
	CONVERSATION_ACCESS_DENIED(
		HttpStatus.FORBIDDEN,
		"MESSAGE_002",
		"대화에 대한 권한이 없습니다."
	),
	CONVERSATION_SELF_NOT_ALLOWED(
		HttpStatus.BAD_REQUEST,
		"MESSAGE_003",
		"자기 자신과의 대화는 생성할 수 없습니다."
	),
	DIRECT_MESSAGE_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"MESSAGE_004",
		"메시지를 찾을 수 없습니다."
	),
	DIRECT_MESSAGE_READ_NOT_ALLOWED(
		HttpStatus.FORBIDDEN,
		"MESSAGE_005",
		"본인에게 온 메시지만 읽음 처리할 수 있습니다."
	);

	private final HttpStatus status;
	private final String code;
	private final String message;
}