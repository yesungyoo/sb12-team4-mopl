package com.mopl.content.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record ContentListResponse(
        List<ContentResponse> contents,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ContentListResponse from(Page<ContentResponse> contentPage) {
        return new ContentListResponse(
                contentPage.getContent(),
                contentPage.getNumber(),
                contentPage.getSize(),
                contentPage.getTotalElements(),
                contentPage.getTotalPages()
        );
    }
}
