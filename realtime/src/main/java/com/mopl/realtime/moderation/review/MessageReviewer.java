package com.mopl.realtime.moderation.review;

import com.mopl.realtime.moderation.dto.MessageReviewContext;
import com.mopl.realtime.moderation.dto.MessageReviewDecision;

public interface MessageReviewer {
    MessageReviewDecision review(MessageReviewContext context);
}
