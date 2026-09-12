package com.mopl.batch.content.mapper;

import com.mopl.batch.external.common.dto.ExternalContentDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ContentBulkMapper {

    int bulkUpsert(
            @Param("contents") List<ExternalContentDto> contents
    );
}
