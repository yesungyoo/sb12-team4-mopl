package com.mopl.common.exception.message;

import com.mopl.common.exception.MoplException;

public class DirectMessageNotFoundException extends MoplException {

	public DirectMessageNotFoundException() {
		super(MessageErrorCode.DIRECT_MESSAGE_NOT_FOUND);
	}
}