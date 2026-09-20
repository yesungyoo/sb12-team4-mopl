package com.mopl.common.exception.message;

import com.mopl.common.exception.MoplException;

public class ConversationNotFoundException extends MoplException {

	public ConversationNotFoundException() {
		super(MessageErrorCode.CONVERSATION_NOT_FOUND);
	}
}