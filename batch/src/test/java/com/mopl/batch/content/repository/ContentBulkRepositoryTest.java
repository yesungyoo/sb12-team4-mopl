package com.mopl.batch.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.batch.content.kafka.ContentSearchSyncAfterCommitPublisher;
import com.mopl.batch.content.kafka.ContentSearchSyncTarget;
import com.mopl.batch.content.mapper.ContentBulkMapper;
import com.mopl.batch.content.mapper.ContentExternalIdentifier;
import com.mopl.batch.content.mapper.ContentSearchSyncRow;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ContentBulkRepositoryTest {

    @Mock
    private ContentBulkMapper contentBulkMapper;

    @Mock
    private ContentSearchSyncAfterCommitPublisher afterCommitPublisher;

    @Captor
    private ArgumentCaptor<List<ContentSearchSyncTarget>> targetCaptor;

    @Captor
    private ArgumentCaptor<List<ContentExternalIdentifier>>
            identifierChunkCaptor;

    private ContentBulkRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ContentBulkRepository(
                contentBulkMapper,
                afterCommitPublisher
        );
        when(contentBulkMapper.bulkUpsert(anyList())).thenReturn(1);
    }

    @Test
    void schedulesResolvedDatabaseUuidAndDeletedState() {
        ExternalContentDto content = createContent("existing-001");
        UUID existingId = UUID.randomUUID();

        when(contentBulkMapper.findSearchSyncRows(anyList()))
                .thenReturn(List.of(new ContentSearchSyncRow(
                        existingId.toString(),
                        content.externalSource(),
                        content.type(),
                        content.externalId(),
                        true
                )));

        repository.upsertAll(List.of(content));

        verify(afterCommitPublisher).publishAfterCommit(
                targetCaptor.capture()
        );
        assertThat(targetCaptor.getValue()).containsExactly(
                new ContentSearchSyncTarget(existingId, true)
        );
    }

    @Test
    void failsBeforeSchedulingWhenUuidLookupIsMissing(
            CapturedOutput output
    ) {
        ExternalContentDto content = createContent("missing-001");
        when(contentBulkMapper.findSearchSyncRows(anyList()))
                .thenReturn(List.of());

        assertThatThrownBy(
                () -> repository.upsertAll(List.of(content))
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("누락");

        verify(afterCommitPublisher, never())
                .publishAfterCommit(anyList());
        assertThat(output)
                .contains("UUID 조회 결과가 누락")
                .contains("DB 트랜잭션을 롤백");
    }

    @Test
    void failsBeforeSchedulingWhenUuidLookupIsDuplicated() {
        ExternalContentDto content = createContent("duplicate-001");
        ContentSearchSyncRow first = new ContentSearchSyncRow(
                UUID.randomUUID().toString(),
                content.externalSource(),
                content.type(),
                content.externalId(),
                false
        );
        ContentSearchSyncRow duplicate = new ContentSearchSyncRow(
                UUID.randomUUID().toString(),
                content.externalSource(),
                content.type(),
                content.externalId(),
                false
        );
        when(contentBulkMapper.findSearchSyncRows(anyList()))
                .thenReturn(List.of(first, duplicate));

        assertThatThrownBy(
                () -> repository.upsertAll(List.of(content))
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복");

        verify(afterCommitPublisher, never())
                .publishAfterCommit(anyList());
    }

    @Test
    void chunksLargeUuidLookupAndSchedulesEveryDistinctContent() {
        List<ExternalContentDto> contents = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            contents.add(createContent("large-" + index));
        }

        when(contentBulkMapper.findSearchSyncRows(anyList()))
                .thenAnswer(invocation -> {
                    List<ContentExternalIdentifier> identifiers =
                            invocation.getArgument(0);
                    return identifiers.stream()
                            .map(identifier -> new ContentSearchSyncRow(
                                    stableUuid(identifier).toString(),
                                    identifier.externalSource(),
                                    identifier.type(),
                                    identifier.externalId(),
                                    false
                            ))
                            .toList();
                });

        repository.upsertAll(contents);

        verify(contentBulkMapper, times(2)).findSearchSyncRows(
                identifierChunkCaptor.capture()
        );
        assertThat(identifierChunkCaptor.getAllValues())
                .extracting(List::size)
                .containsExactly(500, 1);

        verify(afterCommitPublisher).publishAfterCommit(
                targetCaptor.capture()
        );
        assertThat(targetCaptor.getValue()).hasSize(501);
    }

    private UUID stableUuid(ContentExternalIdentifier identifier) {
        return UUID.nameUUIDFromBytes(
                identifier.externalId().getBytes(StandardCharsets.UTF_8)
        );
    }

    private ExternalContentDto createContent(String externalId) {
        return new ExternalContentDto(
                "MOVIE",
                "테스트 영화",
                "테스트 설명",
                "https://example.com/test.jpg",
                "TMDB",
                externalId,
                LocalDate.of(2026, 9, 12),
                100.0,
                4.5,
                100L,
                List.of()
        );
    }
}
