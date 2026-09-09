package com.mopl.content.controller;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.service.ContentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;
import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/contents")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/{contentId}")
    public ResponseEntity<ContentResponse> getContent(@PathVariable UUID contentId) {
        ContentResponse response = contentService.getContent(contentId);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<ContentListResponse> getContents(
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {
        ContentListResponse response = contentService.getContents(pageable);

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
