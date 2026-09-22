package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentSearchIndexer {

    private static final int BATCH_SIZE = 500;

    private final ContentRepository contentRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ContentEmbeddingService contentEmbeddingService;

    public long reindexAll() {
        // TODO 운영 환경 재색인 시 신규 인덱스 생성 후 alias swap 방식으로 전환
        contentSearchRepository.deleteAll();

        int pageNumber = 0;
        long indexedCount = 0;
        Page<Content> contentPage;

        do {
            Pageable pageable = PageRequest.of(
                    pageNumber,
                    BATCH_SIZE,
                    Sort.by(Sort.Direction.ASC, "id")
            );

            contentPage = contentRepository.findAllByDeletedAtIsNull(pageable);

            List<ContentSearchDocument> documents = contentPage.getContent().stream()
                    .map(this::createDocument)
                    .toList();

            if (!documents.isEmpty()) {
                contentSearchRepository.saveAll(documents);
                indexedCount += documents.size();
            }

            pageNumber++;
        } while (contentPage.hasNext());

        return indexedCount;
    }

    public void index(Content content) {
        ContentSearchDocument document = createDocument(content);

        contentSearchRepository.save(document);
    }

    public void delete(UUID contentId) {
        contentSearchRepository.deleteById(contentId.toString());
    }

    private ContentSearchDocument createDocument(Content content) {
        List<Double> embedding = contentEmbeddingService.embedContent(content);

        return ContentSearchDocument.from(content, embedding);
    }

}
