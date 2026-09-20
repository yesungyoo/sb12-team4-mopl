package com.mopl.common.exception.message;

import com.mopl.common.exception.MoplException;

public class ConversationSelfNotAllowedException extends MoplException {

	public ConversationSelfNotAllowedException() {
		super(MessageErrorCode.CONVERSATION_SELF_NOT_ALLOWED);
	}
}