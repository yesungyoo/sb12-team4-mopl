package com.mopl.content.search.event;

import com.mopl.content.search.service.ContentSearchIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSearchSyncEventListener {

    private final ContentSearchIndexer contentSearchIndexer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ContentSearchStatisticsSyncEvent event) {
        try {
            contentSearchIndexer.updateStatistics(event.contentId());
        } catch (Exception e) {
            log.error(
                    "Elasticsearch 콘텐츠 통계 동기화 실패. contentId={}",
                    event.contentId(),
                    e
            );
        }
    }
}
