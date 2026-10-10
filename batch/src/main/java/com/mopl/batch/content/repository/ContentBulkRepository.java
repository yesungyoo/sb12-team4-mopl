package com.mopl.batch.content.repository;

import com.mopl.batch.content.kafka.ContentSearchSyncAfterCommitPublisher;
import com.mopl.batch.content.kafka.ContentSearchSyncTarget;
import com.mopl.batch.content.mapper.ContentExternalIdentifier;
import com.mopl.batch.content.mapper.ContentBulkMapper;
import com.mopl.batch.content.mapper.ContentSearchSyncRow;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentBulkRepository {

    private static final Logger log = LoggerFactory.getLogger(
            ContentBulkRepository.class
    );
    private static final int UUID_LOOKUP_CHUNK_SIZE = 500;
    private static final int IDENTIFIER_LOG_LIMIT = 10;

    private final ContentBulkMapper contentBulkMapper;
    private final ContentSearchSyncAfterCommitPublisher afterCommitPublisher;

    public ContentBulkRepository(
            ContentBulkMapper contentBulkMapper,
            ContentSearchSyncAfterCommitPublisher afterCommitPublisher
    ) {
        this.contentBulkMapper = contentBulkMapper;
        this.afterCommitPublisher = afterCommitPublisher;
    }

    @Transactional
    public int upsertAll(
            List<ExternalContentDto> contents
    ) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        int affectedRows =
                contentBulkMapper.bulkUpsert(contents);

        contentBulkMapper.deleteTagsByContents(
                contents
        );

        List<ExternalContentDto> taggedContents =
                contents.stream()
                        .filter(content ->
                                content.tags() != null
                                        && !content.tags().isEmpty()
                        )
                        .toList();

        if (!taggedContents.isEmpty()) {
            contentBulkMapper.bulkInsertTags(
                    taggedContents
            );
        }

        List<ContentSearchSyncTarget> syncTargets =
                findSearchSyncTargets(contents);

        afterCommitPublisher.publishAfterCommit(syncTargets);

        log.info(
                "콘텐츠와 태그 DB 저장 처리 완료. 트랜잭션 커밋 대기 중입니다. "
                        + "contentCount={}, syncTargetCount={}, affectedRows={}",
                contents.size(),
                syncTargets.size(),
                affectedRows
        );

        return affectedRows;
    }

    private List<ContentSearchSyncTarget> findSearchSyncTargets(
            List<ExternalContentDto> contents
    ) {
        List<ContentExternalIdentifier> identifiers =
                contents.stream()
                        .map(ContentExternalIdentifier::from)
                        .distinct()
                        .toList();

        Set<ContentExternalIdentifier> requestedIdentifiers =
                new LinkedHashSet<>(identifiers);
        Map<ContentExternalIdentifier, ContentSearchSyncTarget> targetsById =
                new LinkedHashMap<>();

        for (int start = 0; start < identifiers.size();
                start += UUID_LOOKUP_CHUNK_SIZE) {
            int end = Math.min(
                    start + UUID_LOOKUP_CHUNK_SIZE,
                    identifiers.size()
            );

            List<ContentSearchSyncRow> rows =
                    contentBulkMapper.findSearchSyncRows(
                            identifiers.subList(start, end)
                    );

            addSearchSyncTargets(
                    rows,
                    requestedIdentifiers,
                    targetsById
            );
        }

        List<ContentExternalIdentifier> missingIdentifiers =
                identifiers.stream()
                        .filter(identifier ->
                                !targetsById.containsKey(identifier)
                        )
                        .toList();

        if (!missingIdentifiers.isEmpty()) {
            log.error(
                    "Upsert 대상 콘텐츠 UUID 조회 결과가 누락되었습니다. "
                            + "DB 트랜잭션을 롤백합니다. missingCount={}, "
                            + "sampleIdentifiers={}",
                    missingIdentifiers.size(),
                    missingIdentifiers.stream()
                            .limit(IDENTIFIER_LOG_LIMIT)
                            .toList()
            );
            throw new IllegalStateException(
                    "Upsert 대상 콘텐츠 UUID 조회 결과가 누락되었습니다."
            );
        }

        List<ContentSearchSyncTarget> orderedTargets = new ArrayList<>(
                identifiers.size()
        );
        for (ContentExternalIdentifier identifier : identifiers) {
            orderedTargets.add(targetsById.get(identifier));
        }
        return List.copyOf(orderedTargets);
    }

    private void addSearchSyncTargets(
            List<ContentSearchSyncRow> rows,
            Set<ContentExternalIdentifier> requestedIdentifiers,
            Map<ContentExternalIdentifier, ContentSearchSyncTarget> targetsById
    ) {
        for (ContentSearchSyncRow row : rows) {
            ContentExternalIdentifier identifier = row.identifier();

            if (!requestedIdentifiers.contains(identifier)) {
                log.error(
                        "요청하지 않은 콘텐츠 UUID 조회 결과가 반환되었습니다. "
                                + "identifier={}",
                        identifier
                );
                throw new IllegalStateException(
                        "요청하지 않은 콘텐츠 UUID 조회 결과가 반환되었습니다."
                );
            }

            ContentSearchSyncTarget target;
            try {
                target = new ContentSearchSyncTarget(
                        UUID.fromString(row.contentId()),
                        Boolean.TRUE.equals(row.deleted())
                );
            } catch (RuntimeException exception) {
                log.error(
                        "콘텐츠 UUID 조회 결과를 변환할 수 없습니다. "
                                + "identifier={}, contentId={}",
                        identifier,
                        row.contentId(),
                        exception
                );
                throw new IllegalStateException(
                        "콘텐츠 UUID 조회 결과를 변환할 수 없습니다.",
                        exception
                );
            }

            ContentSearchSyncTarget previous = targetsById.putIfAbsent(
                    identifier,
                    target
            );

            if (previous != null) {
                log.error(
                        "콘텐츠 UUID 조회 결과가 중복되었습니다. "
                                + "identifier={}, firstContentId={}, "
                                + "duplicateContentId={}",
                        identifier,
                        previous.contentId(),
                        target.contentId()
                );
                throw new IllegalStateException(
                        "콘텐츠 UUID 조회 결과가 중복되었습니다."
                );
            }
        }
    }
}
