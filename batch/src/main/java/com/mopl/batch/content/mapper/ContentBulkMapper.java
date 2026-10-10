package com.mopl.batch.content.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentBulkMapper {

    int bulkUpsert(
            @Param("contents")
            List<ExternalContentDto> contents
    );

    int deleteTagsByContents(
            @Param("contents")
            List<ExternalContentDto> contents
    );

    int bulkInsertTags(
            @Param("contents")
            List<ExternalContentDto> contents
    );

    List<ContentSearchSyncRow> findSearchSyncRows(
            @Param("identifiers")
            List<ContentExternalIdentifier> identifiers
    );
}
