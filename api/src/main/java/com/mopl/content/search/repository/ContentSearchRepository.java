package com.mopl.content.search.repository;

import com.mopl.content.search.document.ContentSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ContentSearchRepository extends ElasticsearchRepository<ContentSearchDocument, String> {
}
