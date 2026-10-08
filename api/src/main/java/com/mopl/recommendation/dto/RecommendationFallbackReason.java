package com.mopl.recommendation.dto;

public enum RecommendationFallbackReason {
    AI_DISABLED,
    API_KEY_MISSING,
    EMBEDDING_API_FAILED,
    NO_SEMANTIC_CANDIDATE,
    ALL_CANDIDATES_EXCLUDED
}
