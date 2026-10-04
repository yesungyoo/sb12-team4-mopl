package com.mopl.content.search.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.repository.ContentTagRepository;
import com.mopl.content.repository.ContentViewRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.review.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

@ExtendWith(MockitoExtension.class)
class ContentSearchIndexerTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentTagRepository contentTagRepository;

    @Mock
    private ContentSearchRepository contentSearchRepository;

    @Mock
    private ContentEmbeddingService contentEmbeddingService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ContentViewRepository contentViewRepository;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private IndexOperations indexOperations;

    @InjectMocks
    private ContentSearchIndexer contentSearchIndexer;

    @Test
    void reindexAllRecreatesIndexWithCurrentMapping() {
        when(elasticsearchOperations.indexOps(ContentSearchDocument.class))
                .thenReturn(indexOperations);

        when(indexOperations.exists())
                .thenReturn(true);

        when(contentRepository.findAllByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(Page.empty());

        contentSearchIndexer.reindexAll();

        verify(indexOperations).delete();
        verify(indexOperations).createWithMapping();
    }
}
