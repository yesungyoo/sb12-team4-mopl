package com.mopl.content.search.event;

import com.mopl.content.repository.ContentRepository;
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

    private final ContentRepository contentRepository;
    private final ContentSearchIndexer contentSearchIndexer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ContentSearchSyncEvent event) {
        try {
            if (event.deleted()) {
                contentSearchIndexer.delete(event.contentId());
                return;
            }

            contentRepository.findByIdAndDeletedAtIsNull(event.contentId())
                    .ifPresent(contentSearchIndexer::index);
        } catch (Exception e) {
            // TODO Kafka 기반 재처리 구조 도입 후 Elasticsearch 동기화 실패 복구 처리
            log.error("Elasticsearch 콘텐츠 동기화 실패. contentId={}", event.contentId(), e);
        }
    }

    // 리뷰/조회 통계만 갱신
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handle(ContentSearchStatisticsSyncEvent event) {
        try {
            contentSearchIndexer.updateStatistics(
                    event.contentId()
            );
        } catch (Exception e) {
            log.error(
                    "Elasticsearch 콘텐츠 통계 동기화 실패, contentId={}",
                    event.contentId(),
                    e
            );
        }
    }
}
