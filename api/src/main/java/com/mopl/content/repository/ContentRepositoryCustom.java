package com.mopl.content.repository;

import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.core.domain.content.entity.Content;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ContentRepositoryCustom {

    Page<Content> search(ContentSearchCondition condition, Pageable pageable);
}
