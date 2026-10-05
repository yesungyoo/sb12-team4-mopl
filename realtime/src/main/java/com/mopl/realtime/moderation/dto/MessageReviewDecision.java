package com.mopl.realtime.moderation.dto;

import java.util.Objects;

public record MessageReviewDecision(Action action) {
    public MessageReviewDecision {
        Objects.requireNonNull(action, "action");
    }

    public enum Action { ALLOW, VIOLATION }
}
