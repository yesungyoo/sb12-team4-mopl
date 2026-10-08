package com.mopl.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.user.entity.User;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.dto.RecommendationPreference;
import com.mopl.recommendation.dto.RecommendationPreferredTag;
import com.mopl.recommendation.dto.RecommendationSectionItem;
import com.mopl.recommendation.dto.RecommendationSectionsResponse;
import com.mopl.recommendation.dto.RecommendationTab;
import com.mopl.recommendation.dto.RecommendationResult;
import com.mopl.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationSectionServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-10-04T16:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private RecommendationPreferenceService recommendationPreferenceService;

    @Mock
    private RecommendationSectionSearchService recommendationSectionSearchService;

    @Mock
    private UserRepository userRepository;

    private RecommendationSectionService recommendationSectionService;

    @BeforeEach
    void setUp() {
        recommendationSectionService =
                new RecommendationSectionService(
                        recommendationService,
                        recommendationPreferenceService,
                        recommendationSectionSearchService,
                        userRepository,
                        FIXED_CLOCK
                );
    }

    // 이 아래의 기존 테스트들은
    // newTabPassesPreviouslyUsedIdsToFollowingSearches() 전까지 그대로 유지한다.

    @Test
    void newTabAllowsGenericNewContentToAppearInTypeSection() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreference preference =
                RecommendationPreference.forColdStart();

        UUID sharedMovieId = UUID.randomUUID();
        UUID tvId = UUID.randomUUID();
        UUID sportId = UUID.randomUUID();

        RecommendationSectionItem genericNew =
                sectionItem(
                        sharedMovieId,
                        "Generic New Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem movie =
                sectionItem(
                        sharedMovieId,
                        "New Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem tv =
                sectionItem(
                        tvId,
                        "New TV",
                        ContentType.TV_SERIES
                );

        RecommendationSectionItem sport =
                sectionItem(
                        sportId,
                        "New Sport",
                        ContentType.SPORT
                );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(recommendationSectionSearchService.findNew(
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(genericNew));

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.MOVIE),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(movie));

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.TV_SERIES),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(tv));

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.SPORT),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(sport));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        RecommendationTab.NEW
                );

        assertThat(response.sections())
                .extracting(section -> section.key())
                .containsExactly(
                        "NEW_CONTENT",
                        "NEW_MOVIE",
                        "NEW_TV_SERIES",
                        "NEW_SPORT"
                );

        assertThat(
                response.sections()
                        .get(0)
                        .items()
                        .getFirst()
                        .contentId()
        ).isEqualTo(sharedMovieId);

        assertThat(
                response.sections()
                        .get(1)
                        .items()
                        .getFirst()
                        .contentId()
        ).isEqualTo(sharedMovieId);

        verify(recommendationSectionSearchService)
                .findNewByType(
                        eq(ContentType.MOVIE),
                        any(LocalDateTime.class),
                        eq(10),
                        org.mockito.ArgumentMatchers.argThat(
                                excludedIds -> excludedIds.isEmpty()
                        )
                );
    }

    @Test
    void newTabUsesExactlyFourteenDaysAgoAsCutoff() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(RecommendationPreference.forColdStart());

        when(recommendationSectionSearchService.findNew(
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        recommendationSectionService.getSections(
                userId,
                RecommendationTab.NEW
        );

        ArgumentCaptor<LocalDateTime> createdAfterCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);

        verify(recommendationSectionSearchService)
                .findNew(
                        createdAfterCaptor.capture(),
                        eq(10),
                        anySet()
                );

        assertThat(createdAfterCaptor.getValue())
                .isEqualTo(
                        LocalDateTime.of(
                                2026,
                                9,
                                21,
                                1,
                                0
                        )
                );
    }

    @Test
    void returnsMovieSectionsInExpectedOrder() {
        assertPersonalizedTypeTab(
                RecommendationTab.MOVIE,
                ContentType.MOVIE,
                "PERSONALIZED_MOVIE",
                "NEW_MOVIE",
                "PREFERENCE_TOP_MOVIE"
        );
    }

    @Test
    void returnsTvSeriesSectionsInExpectedOrder() {
        assertPersonalizedTypeTab(
                RecommendationTab.TV_SERIES,
                ContentType.TV_SERIES,
                "PERSONALIZED_TV_SERIES",
                "NEW_TV_SERIES",
                "PREFERENCE_TOP_TV_SERIES"
        );
    }

    @Test
    void returnsSportSectionsInExpectedOrder() {
        assertPersonalizedTypeTab(
                RecommendationTab.SPORT,
                ContentType.SPORT,
                "PERSONALIZED_SPORT",
                "NEW_SPORT",
                "PREFERENCE_TOP_SPORT"
        );
    }

    @Test
    void coldStartMovieTabUsesPopularFallbackAndOmitsPreferenceSection() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreference preference =
                RecommendationPreference.forColdStart();

        UUID popularContentId = UUID.randomUUID();
        UUID newContentId = UUID.randomUUID();

        RecommendationSectionItem popularItem =
                sectionItem(
                        popularContentId,
                        "Popular Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem newItem =
                sectionItem(
                        newContentId,
                        "New Movie",
                        ContentType.MOVIE
                );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(recommendationService.getRecommendationResult(userId))
                .thenReturn(RecommendationResult.popular(List.of()));

        when(recommendationSectionSearchService.findPopularByType(
                eq(ContentType.MOVIE),
                eq(10),
                anySet()
        )).thenReturn(List.of(popularItem));

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.MOVIE),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(newItem));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        RecommendationTab.MOVIE
                );

        assertThat(response.sections())
                .extracting(section -> section.key())
                .containsExactly(
                        "POPULAR_MOVIE",
                        "NEW_MOVIE"
                );
    }

    @Test
    void personalizedHomeUsesPopularLabelWhenRecommendationFallsBack() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");
        UUID interactedContentId = UUID.randomUUID();
        UUID popularContentId = UUID.randomUUID();

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        "preference",
                        Set.of(interactedContentId)
                );
        RecommendationItem popularRecommendation = recommendationItem(
                popularContentId,
                "Popular Movie",
                ContentType.MOVIE
        );
        RecommendationSectionItem popularItem = sectionItem(
                popularContentId,
                "Popular Movie",
                ContentType.MOVIE
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));
        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);
        when(recommendationService.getRecommendationResult(userId))
                .thenReturn(RecommendationResult.popular(
                        List.of(popularRecommendation)
                ));
        when(recommendationSectionSearchService.findByContentIds(
                List.of(popularContentId)
        )).thenReturn(List.of(popularItem));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        RecommendationTab.HOME
                );

        assertThat(response.sections().getFirst().key())
                .isEqualTo("POPULAR");
        assertThat(response.sections().getFirst().items())
                .extracting(RecommendationSectionItem::contentId)
                .containsExactly(popularContentId);
    }

    @Test
    void popularFallbackQueriesEachTypeDirectlyAndExcludesInteractions() {
        assertPopularFallbackTypeTab(
                RecommendationTab.MOVIE,
                ContentType.MOVIE,
                "POPULAR_MOVIE"
        );
        assertPopularFallbackTypeTab(
                RecommendationTab.TV_SERIES,
                ContentType.TV_SERIES,
                "POPULAR_TV_SERIES"
        );
        assertPopularFallbackTypeTab(
                RecommendationTab.SPORT,
                ContentType.SPORT,
                "POPULAR_SPORT"
        );
    }

    private void assertPersonalizedTypeTab(
            RecommendationTab tab,
            ContentType contentType,
            String personalizedKey,
            String newKey,
            String preferenceKey
    ) {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreferredTag preferredTag =
                new RecommendationPreferredTag(
                        "TAG",
                        "VALUE",
                        10L
                );

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        "preference",
                        Set.of(),
                        Map.of(
                                contentType,
                                List.of(preferredTag)
                        )
                );

        UUID personalizedContentId = UUID.randomUUID();
        UUID newContentId = UUID.randomUUID();
        UUID preferenceContentId = UUID.randomUUID();

        RecommendationItem recommendation =
                recommendationItem(
                        personalizedContentId,
                        "Personalized",
                        contentType
                );

        RecommendationSectionItem personalizedItem =
                sectionItem(
                        personalizedContentId,
                        "Personalized",
                        contentType
                );

        RecommendationSectionItem newItem =
                sectionItem(
                        newContentId,
                        "New",
                        contentType
                );

        RecommendationSectionItem preferenceItem =
                sectionItem(
                        preferenceContentId,
                        "Preference",
                        contentType
                );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(recommendationService.getRecommendationResult(userId))
                .thenReturn(RecommendationResult.personalized(
                        List.of(recommendation)
                ));

        when(recommendationSectionSearchService.findByContentIds(
                List.of(personalizedContentId)
        )).thenReturn(List.of(personalizedItem));

        when(recommendationSectionSearchService.findNewByType(
                eq(contentType),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(newItem));

        when(recommendationSectionSearchService.findPopularByPreferenceTag(
                eq(contentType),
                eq(preferredTag),
                eq(10),
                anySet()
        )).thenReturn(List.of(preferenceItem));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        tab
                );

        assertThat(response.sections())
                .extracting(section -> section.key())
                .containsExactly(
                        personalizedKey,
                        newKey,
                        preferenceKey
                );

        verify(recommendationSectionSearchService)
                .findNewByType(
                        eq(contentType),
                        any(LocalDateTime.class),
                        eq(10),
                        org.mockito.ArgumentMatchers.argThat(
                                excludedIds ->
                                        excludedIds.contains(
                                                personalizedContentId
                                        )
                        )
                );
    }

    private void assertPopularFallbackTypeTab(
            RecommendationTab tab,
            ContentType contentType,
            String popularKey
    ) {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");
        UUID interactedContentId = UUID.randomUUID();
        UUID popularContentId = UUID.randomUUID();

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        "preference",
                        Set.of(interactedContentId)
                );
        RecommendationSectionItem popularItem = sectionItem(
                popularContentId,
                "Popular",
                contentType
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));
        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);
        when(recommendationService.getRecommendationResult(userId))
                .thenReturn(RecommendationResult.popular(List.of()));
        when(recommendationSectionSearchService.findPopularByType(
                eq(contentType),
                eq(10),
                anySet()
        )).thenReturn(List.of(popularItem));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(userId, tab);

        assertThat(response.sections().getFirst().key())
                .isEqualTo(popularKey);
        assertThat(response.sections().getFirst().items())
                .extracting(RecommendationSectionItem::type)
                .containsOnly(contentType);
        verify(recommendationSectionSearchService)
                .findPopularByType(
                        eq(contentType),
                        eq(10),
                        org.mockito.ArgumentMatchers.argThat(
                                excludedIds -> excludedIds.contains(
                                        interactedContentId
                                )
                        )
                );
    }

    private User mockUser(String name) {
        User user = mock(User.class);

        when(user.getName())
                .thenReturn(name);

        return user;
    }

    private RecommendationItem recommendationItem(
            UUID contentId,
            String title,
            ContentType contentType
    ) {
        return new RecommendationItem(
                contentId,
                title,
                "https://example.com/"
                        + contentId
                        + ".jpg",
                contentType,
                List.of(),
                0.95,
                8.5,
                100.0,
                1000L,
                "추천 이유"
        );
    }

    private RecommendationSectionItem sectionItem(
            UUID contentId,
            String title,
            ContentType contentType
    ) {
        return new RecommendationSectionItem(
                contentId,
                title,
                "https://example.com/"
                        + contentId
                        + ".jpg",
                contentType,
                List.of(),
                4.5,
                10L,
                100L,
                LocalDateTime.of(
                        2026,
                        10,
                        1,
                        12,
                        0
                )
        );
    }
}
