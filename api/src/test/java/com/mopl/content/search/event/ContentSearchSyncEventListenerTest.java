package com.mopl.content.search.event;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.service.ContentSearchIndexer;
import com.mopl.core.domain.content.entity.Content;

@ExtendWith(MockitoExtension.class)
public class ContentSearchSyncEventListenerTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentSearchIndexer contentSearchIndexer;

    @Mock
    private Content content;

    private ContentSearchSyncEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ContentSearchSyncEventListener(contentRepository, contentSearchIndexer);
    }

    @Test
    void deleteDocumentWhenContentIsDeleted() {
        UUID contentId = UUID.randomUUID();
        ContentSearchSyncEvent event = new ContentSearchSyncEvent(contentId, true);

        listener.handle(event);

        verify(contentSearchIndexer).delete(contentId);
        verify(contentRepository, never()).findByIdAndDeletedAtIsNull(contentId);
    }

    @Test
    void indexesDocumentWhenContentExists() {
        UUID contentId = UUID.randomUUID();
        ContentSearchSyncEvent event =
                new ContentSearchSyncEvent(contentId, false);

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.of(content));

        listener.handle(event);

        verify(contentSearchIndexer).index(content);
    }

    @Test
    void doesNotIndexWhenContentDoesNotExist() {
        UUID contentId = UUID.randomUUID();
        ContentSearchSyncEvent event =
                new ContentSearchSyncEvent(contentId, false);

        when(contentRepository.findByIdAndDeletedAtIsNull(contentId))
                .thenReturn(Optional.empty());

        listener.handle(event);

        verify(contentSearchIndexer, never()).index(content);
    }
}
