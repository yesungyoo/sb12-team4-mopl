package com.mopl.content.repository.projection;

import java.util.UUID;

public interface ContentViewStatisticsProjection {

    UUID getContentId();

    Long getWatcherCount();
}
