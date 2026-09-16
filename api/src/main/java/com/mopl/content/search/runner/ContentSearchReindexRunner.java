package com.mopl.content.search.runner;

import com.mopl.content.search.service.ContentSearchIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "mopl.elasticsearch",
        name = "reindex-on-startup",
        havingValue = "true"
)
public class ContentSearchReindexRunner implements ApplicationRunner {

    private final ContentSearchIndexer contentSearchIndexer;

    @Override
    public void run(ApplicationArguments args) {
        long indexedCount = contentSearchIndexer.reindexAll();

        log.info("Elasticsearch 콘텐츠 전체 재색인 완료. indexedCount={}", indexedCount);
    }
}
