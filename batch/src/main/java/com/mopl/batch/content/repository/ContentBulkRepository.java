package com.mopl.batch.content.repository;

import com.mopl.batch.content.mapper.ContentBulkMapper;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentBulkRepository {

    private final ContentBulkMapper contentBulkMapper;

    public ContentBulkRepository(
            ContentBulkMapper contentBulkMapper
    ) {
        this.contentBulkMapper = contentBulkMapper;
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

        return affectedRows;
    }
}
