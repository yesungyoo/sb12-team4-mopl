package com.mopl.content.controller;

import com.mopl.content.dto.*;
import com.mopl.content.search.service.SemanticSearchService;
import com.mopl.content.service.ContentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;
import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;
    private final SemanticSearchService semanticSearchService;

    @GetMapping("/{contentId}")
    public ResponseEntity<ContentResponse> getContent(@PathVariable UUID contentId) {
        ContentResponse response = contentService.getContent(contentId);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<ContentListResponse> getContents(
            @ModelAttribute ContentSearchCondition condition,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {
        ContentListResponse response = contentService.getContents(condition, pageable);

        return ResponseEntity.ok(response);
    }

    // Elasticsearch vector similarity 기반 시맨틱 검색 API
    // - 정렬 조건을 별도로 지정하지 않아 Elasticsearch의 유사도 점수 순서를 유지
    @GetMapping("/semantic-search")
    public ResponseEntity<ContentListResponse> searchSemanticContents(@RequestParam @NotBlank String query,
                                                                      @PageableDefault(size = 20) Pageable pageable) {
        ContentListResponse response = semanticSearchService.search(query, pageable);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ContentResponse> createContent(@Valid @RequestBody ContentCreateRequest request) {
        ContentResponse response = contentService.createContent(request);

        URI location = URI.create("/contents/" + response.id());

        return ResponseEntity
                .created(location)
                .body(response);
    }

    @PatchMapping("/{contentId}")
    public ResponseEntity<ContentResponse> updateContent(@PathVariable UUID contentId,
                                                         @Valid @RequestBody ContentUpdateRequest request) {
        ContentResponse response = contentService.updateContent(contentId, request);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{contentId}")
    public ResponseEntity<Void> deleteContent(@PathVariable UUID contentId) {
        contentService.deleteContent(contentId);

        return ResponseEntity.noContent().build();
    }
}
