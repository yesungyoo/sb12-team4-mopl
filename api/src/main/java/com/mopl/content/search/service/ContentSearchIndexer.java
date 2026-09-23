package com.mopl.content.search.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.core.domain.content.entity.Content;
import com.mopl.core.domain.content.entity.ContentTag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentSearchIndexer {

    private static final int BATCH_SIZE = 500;

    private final ContentRepository contentRepository;
    private final ContentTagRepository contentTagRepository;
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

            List<Content> contents = contentPage.getContent();

            Map<UUID, List<ContentTag>> tagsByContentId = findTagsByContentId(contents);

            List<ContentSearchDocument> documents = contents.stream()
                    .map(content -> createDocument(
                            content,
                            tagsByContentId.getOrDefault(content.getId(), List.of())
                    ))
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
        List<ContentTag> contentTags =
                contentTagRepository.findAllByContentId(content.getId());

        ContentSearchDocument document = createDocument(content, contentTags);

        contentSearchRepository.save(document);
    }

    public void delete(UUID contentId) {
        contentSearchRepository.deleteById(contentId.toString());
    }

    private Map<UUID, List<ContentTag>> findTagsByContentId(List<Content> contents) {
        if (contents.isEmpty()) {
            return Map.of();
        }

        List<UUID> contentIds = contents.stream()
                .map(Content::getId)
                .toList();

        return contentTagRepository.findAllByContentIds(contentIds)
                .stream()
                .collect(Collectors.groupingBy(contentTag ->
                        contentTag.getContent().getId()));
    }

    private ContentSearchDocument createDocument(Content content, List<ContentTag> contentTags) {
        List<Double> embedding = contentEmbeddingService.embedContent(content, contentTags);

        return ContentSearchDocument.from(content, contentTags, embedding);
    }

}
