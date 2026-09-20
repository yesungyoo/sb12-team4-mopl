package com.mopl.common.exception.message;

import com.mopl.common.exception.MoplException;

public class ConversationAccessDeniedException extends MoplException {

	public ConversationAccessDeniedException() {
		super(MessageErrorCode.CONVERSATION_ACCESS_DENIED);
	}
}