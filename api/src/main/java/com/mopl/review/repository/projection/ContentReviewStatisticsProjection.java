package com.mopl.review.repository.projection;

import java.util.UUID;

public interface ContentReviewStatisticsProjection {

    UUID getContentId();

    Double getAverageRating();

    Long getReviewCount();
}
