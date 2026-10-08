package com.mopl.common.exception.content;

import com.mopl.common.exception.MoplException;

public class SemanticSearchUnavailableException extends MoplException {

    public SemanticSearchUnavailableException() {
        super(ContentErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
    }
}
