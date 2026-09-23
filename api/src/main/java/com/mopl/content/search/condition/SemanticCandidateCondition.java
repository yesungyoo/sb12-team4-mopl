package com.mopl.content.search.condition;

import com.mopl.core.common.enums.ContentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SemanticCandidateCondition(
        ContentType type,
        List<ContentTagCondition> tags
) {

    public SemanticCandidateCondition {
        // 호출부에서 null을 전달해도 서비스 내부에서는 빈 목록으로 처리
        tags = tags == null
                ? List.of()
                : Collections.unmodifiableList(
                        new ArrayList<>(tags)
        );

    }
}
