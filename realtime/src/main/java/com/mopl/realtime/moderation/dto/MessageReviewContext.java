package com.mopl.realtime.moderation.dto;

import java.util.List;
import java.util.Objects;

public record MessageReviewContext(String currentMessage, List<ConversationMessage> recentConversation) {
    public MessageReviewContext {
        Objects.requireNonNull(currentMessage, "currentMessage");
        recentConversation = List.copyOf(recentConversation);
    }
    public record ConversationMessage(boolean fromCurrentSender, String text) {
        @Override public String toString() { return "ConversationMessage[fromCurrentSender=" + fromCurrentSender + "]"; }
    }
    @Override public String toString() { return "MessageReviewContext[conversationSize=" + recentConversation.size() + "]"; }
}
