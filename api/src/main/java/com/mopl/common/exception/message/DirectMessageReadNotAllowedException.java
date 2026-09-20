package com.mopl.common.exception.message;

import com.mopl.common.exception.MoplException;

public class DirectMessageReadNotAllowedException extends MoplException {

	public DirectMessageReadNotAllowedException() {
		super(MessageErrorCode.DIRECT_MESSAGE_READ_NOT_ALLOWED);
	}
}