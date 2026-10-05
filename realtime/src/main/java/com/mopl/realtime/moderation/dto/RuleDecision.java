package com.mopl.realtime.moderation.dto;

import java.util.Objects;

public record RuleDecision(RuleAction action, String content) {
    public RuleDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(content, "content");
    }
    @Override public String toString() { return "RuleDecision[action=" + action + "]"; }
}
