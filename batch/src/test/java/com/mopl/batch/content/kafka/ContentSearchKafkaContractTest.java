package com.mopl.batch.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.core.common.kafka.ContentSearchKafkaTopics;
import com.mopl.core.common.kafka.ContentSearchSyncKafkaEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentSearchKafkaContractTest {

    @Test
    void referencesSharedContentSearchContractFromBatch() {
        UUID contentId = UUID.randomUUID();

        ContentSearchSyncKafkaEvent event =
                new ContentSearchSyncKafkaEvent(
                        contentId,
                        false
                );

        assertThat(event.contentId()).isEqualTo(contentId);
        assertThat(event.deleted()).isFalse();
        assertThat(ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC)
                .isEqualTo("content-search-sync");
    }
}
