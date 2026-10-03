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
import com.mopl.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationSectionServiceTest {

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
                        userRepository
                );
    }

    @Test
    void returnsPersonalizedHomeSectionsInExpectedOrder() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreferredTag movieTag =
                new RecommendationPreferredTag(
                        "GENRE",
                        "SF",
                        10L
                );

        RecommendationPreferredTag sportTag =
                new RecommendationPreferredTag(
                        "SPORT",
                        "F1",
                        8L
                );

        RecommendationPreferredTag tvTag =
                new RecommendationPreferredTag(
                        "GENRE",
                        "MYSTERY",
                        7L
                );

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        "preference",
                        Set.of(),
                        Map.of(
                                ContentType.MOVIE,
                                List.of(movieTag),
                                ContentType.SPORT,
                                List.of(sportTag),
                                ContentType.TV_SERIES,
                                List.of(tvTag)
                        )
                );

        UUID aiContentId = UUID.randomUUID();
        UUID newContentId = UUID.randomUUID();
        UUID movieContentId = UUID.randomUUID();
        UUID sportContentId = UUID.randomUUID();
        UUID tvContentId = UUID.randomUUID();

        RecommendationItem aiRecommendation =
                recommendationItem(
                        aiContentId,
                        "AI Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem aiItem =
                sectionItem(
                        aiContentId,
                        "AI Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem newItem =
                sectionItem(
                        newContentId,
                        "New Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem movieItem =
                sectionItem(
                        movieContentId,
                        "SF Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem sportItem =
                sectionItem(
                        sportContentId,
                        "F1 Content",
                        ContentType.SPORT
                );

        RecommendationSectionItem tvItem =
                sectionItem(
                        tvContentId,
                        "Mystery TV",
                        ContentType.TV_SERIES
                );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(recommendationService.getRecommendations(userId))
                .thenReturn(List.of(aiRecommendation));

        when(recommendationSectionSearchService.findByContentIds(
                List.of(aiContentId)
        )).thenReturn(List.of(aiItem));

        when(recommendationSectionSearchService.findNewByPreferenceTag(
                eq(ContentType.MOVIE),
                eq(movieTag),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(newItem));

        when(recommendationSectionSearchService.findNewByPreferenceTag(
                eq(ContentType.SPORT),
                eq(sportTag),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        when(recommendationSectionSearchService.findNewByPreferenceTag(
                eq(ContentType.TV_SERIES),
                eq(tvTag),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        when(recommendationSectionSearchService.findPopularByPreferenceTag(
                eq(ContentType.MOVIE),
                eq(movieTag),
                eq(10),
                anySet()
        )).thenReturn(List.of(movieItem));

        when(recommendationSectionSearchService.findPopularByPreferenceTag(
                eq(ContentType.SPORT),
                eq(sportTag),
                eq(10),
                anySet()
        )).thenReturn(List.of(sportItem));

        when(recommendationSectionSearchService.findPopularByPreferenceTag(
                eq(ContentType.TV_SERIES),
                eq(tvTag),
                eq(10),
                anySet()
        )).thenReturn(List.of(tvItem));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        RecommendationTab.HOME
                );

        assertThat(response.sections())
                .extracting(section -> section.key())
                .containsExactly(
                        "AI_PERSONALIZED",
                        "NEW_FOR_YOU",
                        "PREFERENCE_TOP_MOVIE",
                        "PREFERENCE_TOP_SPORT",
                        "PREFERENCE_TOP_TV_SERIES"
                );
    }

    @Test
    void returnsMultiplePreferenceSectionsForSameContentType() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreferredTag sfTag =
                new RecommendationPreferredTag(
                        "GENRE",
                        "SF",
                        10L
                );

        RecommendationPreferredTag actionTag =
                new RecommendationPreferredTag(
                        "GENRE",
                        "ACTION",
                        8L
                );

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        "preference",
                        Set.of(),
                        Map.of(
                                ContentType.MOVIE,
                                List.of(
                                        sfTag,
                                        actionTag
                                )
                        )
                );

        UUID personalizedContentId = UUID.randomUUID();
        UUID sfContentId = UUID.randomUUID();
        UUID actionContentId = UUID.randomUUID();

        RecommendationItem recommendation =
                recommendationItem(
                        personalizedContentId,
                        "Personalized",
                        ContentType.MOVIE
                );

        RecommendationSectionItem personalizedItem =
                sectionItem(
                        personalizedContentId,
                        "Personalized",
                        ContentType.MOVIE
                );

        RecommendationSectionItem sfItem =
                sectionItem(
                        sfContentId,
                        "SF Movie",
                        ContentType.MOVIE
                );

        RecommendationSectionItem actionItem =
                sectionItem(
                        actionContentId,
                        "Action Movie",
                        ContentType.MOVIE
                );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(recommendationService.getRecommendations(userId))
                .thenReturn(List.of(recommendation));

        when(recommendationSectionSearchService.findByContentIds(
                List.of(personalizedContentId)
        )).thenReturn(List.of(personalizedItem));

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.MOVIE),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        when(recommendationSectionSearchService.findPopularByPreferenceTag(
                eq(ContentType.MOVIE),
                eq(sfTag),
                eq(10),
                anySet()
        )).thenReturn(List.of(sfItem));

        when(recommendationSectionSearchService.findPopularByPreferenceTag(
                eq(ContentType.MOVIE),
                eq(actionTag),
                eq(10),
                anySet()
        )).thenReturn(List.of(actionItem));

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        RecommendationTab.MOVIE
                );

        assertThat(response.sections())
                .extracting(section -> section.key())
                .containsExactly(
                        "PERSONALIZED_MOVIE",
                        "PREFERENCE_TOP_MOVIE",
                        "PREFERENCE_TOP_MOVIE_2"
                );

        assertThat(response.sections())
                .extracting(section -> section.tag())
                .containsExactly(
                        null,
                        "SF",
                        "ACTION"
                );
    }

    @Test
    void newTabUsesAllPreferredTagsForPersonalizedNewSection() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreferredTag sfTag =
                new RecommendationPreferredTag(
                        "GENRE",
                        "SF",
                        10L
                );

        RecommendationPreferredTag actionTag =
                new RecommendationPreferredTag(
                        "GENRE",
                        "ACTION",
                        8L
                );

        RecommendationPreference preference =
                RecommendationPreference.personalized(
                        "preference",
                        Set.of(),
                        Map.of(
                                ContentType.MOVIE,
                                List.of(
                                        sfTag,
                                        actionTag
                                )
                        )
                );

        UUID newContentId = UUID.randomUUID();

        RecommendationSectionItem newItem =
                sectionItem(
                        newContentId,
                        "New Action Movie",
                        ContentType.MOVIE
                );

        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(java.util.Optional.of(user));

        when(recommendationPreferenceService.createPreference(userId))
                .thenReturn(preference);

        when(recommendationSectionSearchService.findNewByPreferenceTag(
                eq(ContentType.MOVIE),
                eq(sfTag),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        when(recommendationSectionSearchService.findNewByPreferenceTag(
                eq(ContentType.MOVIE),
                eq(actionTag),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of(newItem));

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.MOVIE),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.TV_SERIES),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        when(recommendationSectionSearchService.findNewByType(
                eq(ContentType.SPORT),
                any(LocalDateTime.class),
                eq(10),
                anySet()
        )).thenReturn(List.of());

        RecommendationSectionsResponse response =
                recommendationSectionService.getSections(
                        userId,
                        RecommendationTab.NEW
                );

        assertThat(response.sections())
                .extracting(section -> section.key())
                .containsExactly("NEW_FOR_YOU");

        verify(recommendationSectionSearchService)
                .findNewByPreferenceTag(
                        eq(ContentType.MOVIE),
                        eq(actionTag),
                        any(LocalDateTime.class),
                        eq(10),
                        anySet()
                );
    }

    @Test
    void newTabPassesPreviouslyUsedIdsToFollowingSearches() {
        UUID userId = UUID.randomUUID();
        User user = mockUser("소현");

        RecommendationPreference preference =
                RecommendationPreference.forColdStart();

        UUID genericNewId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        UUID tvId = UUID.randomUUID();
        UUID sportId = UUID.randomUUID();

        RecommendationSectionItem genericNew =
                sectionItem(
                        genericNewId,
                        "Generic New",
                        ContentType.MOVIE
                );

        RecommendationSectionItem movie =
                sectionItem(
                        movieId,
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

        verify(recommendationSectionSearchService)
                .findNewByType(
                        eq(ContentType.MOVIE),
                        any(LocalDateTime.class),
                        eq(10),
                        org.mockito.ArgumentMatchers.argThat(
                                excludedIds ->
                                        excludedIds.contains(
                                                genericNewId
                                        )
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

        when(recommendationService.getRecommendations(userId))
                .thenReturn(List.of(recommendation));

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
