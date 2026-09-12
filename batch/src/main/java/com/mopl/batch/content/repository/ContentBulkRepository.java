package com.mopl.batch.content.repository;

import com.mopl.batch.content.mapper.ContentBulkMapper;
import com.mopl.batch.external.common.dto.ExternalContentDto;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ContentBulkRepository {

    private final ContentBulkMapper contentBulkMapper;

    public ContentBulkRepository(ContentBulkMapper contentBulkMapper) {
        this.contentBulkMapper = contentBulkMapper;
    }

    public int upsertAll(List<ExternalContentDto> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        return contentBulkMapper.bulkUpsert(contents);
    }
}
