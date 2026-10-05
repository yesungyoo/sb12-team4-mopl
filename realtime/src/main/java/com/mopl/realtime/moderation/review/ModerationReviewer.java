package com.mopl.realtime.moderation.review;

import com.mopl.realtime.moderation.service.ModerationContextService;

public interface ModerationReviewer {
    void review(ModerationContextService.ReviewContext context, SanctionTool tool);
}
