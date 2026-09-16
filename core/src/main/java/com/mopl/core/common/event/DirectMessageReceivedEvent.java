package com.mopl.core.common.event;

import java.util.UUID;

public record DirectMessageReceivedEvent(
    UUID receiverId,
    UUID senderId
) {
}