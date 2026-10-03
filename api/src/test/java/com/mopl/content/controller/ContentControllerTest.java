package com.mopl.content.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.common.exception.content.ContentNotFoundException;
import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentListItemResponse;
import com.mopl.content.dto.ContentListResponse;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentSearchCondition;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.search.service.SemanticSearchService;
import com.mopl.content.service.ContentService;
import com.mopl.core.common.dto.CursorResponse;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.common.enums.ExternalSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ContentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ContentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ContentService contentService;

    @MockitoBean
    private SemanticSearchService semanticSearchService;

    @Test
    @DisplayName("콘텐츠 단건 조회에 성공하면 200을 반환한다")
    void getContentSuccess() throws Exception {
        UUID contentId = UUID.randomUUID();
        ContentResponse response =
                createResponse(contentId, "테스트 영화");

        when(contentService.getContent(contentId))
                .thenReturn(response);

        mockMvc.perform(
                        get(
                                "/api/contents/{contentId}",
                                contentId
                        )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(contentId.toString())
                )
                .andExpect(
                        jsonPath("$.title")
                                .value("테스트 영화")
                )
                .andExpect(
                        jsonPath("$.externalSource")
                                .value("MANUAL")
                );
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠를 조회하면 404를 반환한다")
    void getContentNotFound() throws Exception {
        UUID contentId = UUID.randomUUID();

        when(contentService.getContent(contentId))
                .thenThrow(new ContentNotFoundException());

        mockMvc.perform(
                        get(
                                "/api/contents/{contentId}",
                                contentId
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.code")
                                .value("CONTENT_001")
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("콘텐츠를 찾을 수 없습니다.")
                );
    }

    @Test
    @DisplayName("콘텐츠 목록 조회에 성공하면 커서 응답과 200을 반환한다")
    void getContentsSuccess() throws Exception {
        ContentListItemResponse firstContent =
                createListItemResponse(
                        UUID.randomUUID(),
                        "테스트 영화 A"
                );

        ContentListItemResponse secondContent =
                createListItemResponse(
                        UUID.randomUUID(),
                        "테스트 영화 B"
                );

        CursorResponse<ContentListItemResponse> response =
                CursorResponse.of(
                        List.of(
                                firstContent,
                                secondContent
                        ),
                        null,
                        null,
                        false,
                        2L,
                        "createdAt",
                        "DESCENDING"
                );

        when(
                contentService.getContents(
                        any(ContentSearchCondition.class),
                        isNull(),
                        isNull(),
                        eq(20),
                        eq("createdAt"),
                        eq("DESCENDING")
                )
        ).thenReturn(response);

        mockMvc.perform(
                        get("/api/contents")
                                .param(
                                        "typeEqual",
                                        "movie"
                                )
                                .param(
                                        "keywordLike",
                                        "테스트"
                                )
                                .param(
                                        "limit",
                                        "20"
                                )
                                .param(
                                        "sortBy",
                                        "createdAt"
                                )
                                .param(
                                        "sortDirection",
                                        "DESCENDING"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.length()")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.data[0].title")
                                .value("테스트 영화 A")
                )
                .andExpect(
                        jsonPath("$.data[1].title")
                                .value("테스트 영화 B")
                )
                .andExpect(
                        jsonPath("$.hasNext")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$.totalCount")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.sortBy")
                                .value("createdAt")
                )
                .andExpect(
                        jsonPath("$.sortDirection")
                                .value("DESCENDING")
                );
    }

    @Test
    @DisplayName("시맨틱 검색에 성공하면 유사 콘텐츠 목록과 200을 반환한다")
    void searchSemanticContentSuccess() throws Exception {
        ContentResponse firstContent =
                createResponse(
                        UUID.randomUUID(),
                        "Space Journey"
                );

        ContentResponse secondContent =
                createResponse(
                        UUID.randomUUID(),
                        "Interstellar"
                );

        ContentListResponse response =
                new ContentListResponse(
                        List.of(
                                firstContent,
                                secondContent
                        ),
                        0,
                        20,
                        2,
                        1
                );

        when(
                semanticSearchService.search(
                        eq("감동적인 우주 탐험 영화"),
                        any(Pageable.class)
                )
        ).thenReturn(response);

        mockMvc.perform(
                        get("/api/contents/semantic-search")
                                .param(
                                        "query",
                                        "감동적인 우주 탐험 영화"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.contents.length()")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.contents[0].title")
                                .value("Space Journey")
                )
                .andExpect(
                        jsonPath("$.contents[1].title")
                                .value("Interstellar")
                )
                .andExpect(
                        jsonPath("$.page")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(20)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(1)
                );
    }

    @Test
    @Disabled("공통 핸들러의 메서드 파라미터 검증 400 처리 후 활성화 (GlobalExceptionHandler 후속 이슈)")
    @DisplayName("시맨틱 검색어가 누락되면 400을 반환한다")
    void searchSemanticContentMissingQuery() throws Exception {
        mockMvc.perform(
                        get("/api/contents/semantic-search")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    @Disabled("공통 핸들러의 메서드 파라미터 검증 400 처리 후 활성화 (GlobalExceptionHandler 후속 이슈)")
    @DisplayName("시맨틱 검색어가 공백이면 400을 반환한다")
    void searchSemanticContentsBlankQuery() throws Exception {
        mockMvc.perform(
                        get("/api/contents/semantic-search")
                                .param(
                                        "query",
                                        " "
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("콘텐츠를 수동 등록하면 201을 반환한다")
    void createContentSuccess() throws Exception {
        UUID contentId = UUID.randomUUID();

        ContentCreateRequest request =
                new ContentCreateRequest(
                        ContentType.MOVIE,
                        "관리자 등록 영화",
                        "테스트 설명",
                        "https://example.com/image.jpg",
                        LocalDate.of(
                                2026,
                                9,
                                8
                        )
                );

        ContentResponse response =
                createResponse(
                        contentId,
                        "관리자 등록 영화"
                );

        when(
                contentService.createContent(
                        any(ContentCreateRequest.class)
                )
        ).thenReturn(response);

        mockMvc.perform(
                        post("/api/contents")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper.writeValueAsString(
                                                request
                                        )
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Location",
                                "/api/contents/" + contentId
                        )
                )
                .andExpect(
                        jsonPath("$.title")
                                .value("관리자 등록 영화")
                )
                .andExpect(
                        jsonPath("$.externalSource")
                                .value("MANUAL")
                );
    }

    @Test
    @DisplayName("콘텐츠 등록 시 제목이 비어 있으면 400을 반환한다")
    void createContentInvalidTitle() throws Exception {
        String request = """
                {
                    "type": "MOVIE",
                    "title": ""
                }
                """;

        mockMvc.perform(
                        post("/api/contents")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(request)
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("COMMON_001")
                );
    }

    @Test
    @DisplayName("콘텐츠 수정에 성공하면 200을 반환한다")
    void updateContentSuccess() throws Exception {
        UUID contentId = UUID.randomUUID();

        ContentUpdateRequest request =
                new ContentUpdateRequest(
                        null,
                        "수정된 영화",
                        null,
                        null,
                        null
                );

        ContentResponse response =
                createResponse(
                        contentId,
                        "수정된 영화"
                );

        when(
                contentService.updateContent(
                        eq(contentId),
                        any(ContentUpdateRequest.class)
                )
        ).thenReturn(response);

        mockMvc.perform(
                        patch(
                                "/api/contents/{contentId}",
                                contentId
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper.writeValueAsString(
                                                request
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.title")
                                .value("수정된 영화")
                );
    }

    @Test
    @DisplayName("콘텐츠 수정 요청이 비어 있으면 400을 반환한다")
    void updateContentEmptyRequest() throws Exception {
        mockMvc.perform(
                        patch(
                                "/api/contents/{contentId}",
                                UUID.randomUUID()
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("COMMON_001")
                );
    }

    @Test
    @DisplayName("콘텐츠 삭제에 성공하면 204를 반환한다")
    void deleteContentSuccess() throws Exception {
        UUID contentId = UUID.randomUUID();

        doNothing()
                .when(contentService)
                .deleteContent(contentId);

        mockMvc.perform(
                        delete(
                                "/api/contents/{contentId}",
                                contentId
                        )
                )
                .andExpect(status().isNoContent());
    }

    private ContentResponse createResponse(
            UUID contentId,
            String title
    ) {
        return new ContentResponse(
                contentId,
                ContentType.MOVIE,
                title,
                "테스트 설명",
                null,
                ExternalSource.MANUAL,
                null,
                LocalDate.of(
                        2026,
                        9,
                        8
                ),
                null,
                null,
                null,
                LocalDateTime.of(
                        2026,
                        9,
                        8,
                        12,
                        0
                ),
                LocalDateTime.of(
                        2026,
                        9,
                        8,
                        12,
                        0
                )
        );
    }

    private ContentListItemResponse createListItemResponse(
            UUID contentId,
            String title
    ) {
        return new ContentListItemResponse(
                contentId,
                ContentType.MOVIE,
                title,
                "테스트 설명",
                null,
                List.of("SF"),
                4.5,
                10L,
                20L
        );
    }
}
