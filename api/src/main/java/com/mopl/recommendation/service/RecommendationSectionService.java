package com.mopl.recommendation.service;

import com.mopl.common.exception.CommonErrorCode;
import com.mopl.common.exception.MoplException;
import com.mopl.common.exception.user.UserErrorCode;
import com.mopl.core.common.enums.ContentType;
import com.mopl.core.domain.user.entity.User;
import com.mopl.recommendation.dto.RecommendationItem;
import com.mopl.recommendation.dto.RecommendationPreference;
import com.mopl.recommendation.dto.RecommendationPreferredTag;
import com.mopl.recommendation.dto.RecommendationSection;
import com.mopl.recommendation.dto.RecommendationSectionItem;
import com.mopl.recommendation.dto.RecommendationSectionsResponse;
import com.mopl.recommendation.dto.RecommendationTab;
import com.mopl.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RecommendationSectionService {

    private static final int SECTION_SIZE = 10;
    private static final int NEW_CONTENT_DAYS = 14;
    private static final int MAX_PREFERENCE_SECTIONS_PER_TYPE = 2;

    private static final List<ContentType> PREFERENCE_SECTION_TYPES =
            List.of(
                    ContentType.MOVIE,
                    ContentType.SPORT,
                    ContentType.TV_SERIES
            );

    private final RecommendationService recommendationService;
    private final RecommendationPreferenceService recommendationPreferenceService;
    private final RecommendationSectionSearchService recommendationSectionSearchService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public RecommendationSectionService(
            RecommendationService recommendationService,
            RecommendationPreferenceService recommendationPreferenceService,
            RecommendationSectionSearchService recommendationSectionSearchService,
            UserRepository userRepository
    ) {
        this(
                recommendationService,
                recommendationPreferenceService,
                recommendationSectionSearchService,
                userRepository,
                Clock.systemDefaultZone()
        );
    }

    RecommendationSectionService(
            RecommendationService recommendationService,
            RecommendationPreferenceService recommendationPreferenceService,
            RecommendationSectionSearchService recommendationSectionSearchService,
            UserRepository userRepository,
            Clock clock
    ) {
        this.recommendationService = recommendationService;
        this.recommendationPreferenceService = recommendationPreferenceService;
        this.recommendationSectionSearchService = recommendationSectionSearchService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public RecommendationSectionsResponse getSections(UUID userId, RecommendationTab tab) {
        if (tab == null) {
            throw new MoplException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new MoplException(
                        UserErrorCode.USER_NOT_FOUND,
                        "존재하지 않는 사용자입니다. userId=" + userId
                ));

        RecommendationPreference preference =
                recommendationPreferenceService.createPreference(userId);

        return switch (tab) {
            case HOME -> getHomeSections(userId, user.getName(), preference);
            case NEW -> getNewSections(user.getName(), preference);
            case MOVIE -> getTypeSections(
                    userId,
                    user.getName(),
                    ContentType.MOVIE,
                    preference
            );
            case TV_SERIES -> getTypeSections(
                    userId,
                    user.getName(),
                    ContentType.TV_SERIES,
                    preference
            );
            case SPORT -> getTypeSections(
                    userId,
                    user.getName(),
                    ContentType.SPORT,
                    preference
            );
        };
    }

    private RecommendationSectionsResponse getHomeSections(
            UUID userId,
            String userName,
            RecommendationPreference preference
    ) {
        List<RecommendationSection> sections = new ArrayList<>();
        Set<UUID> usedContentIds = new LinkedHashSet<>();

        List<RecommendationSectionItem> recommendationItems =
                takeUnused(
                        findRecommendationItems(userId, null),
                        usedContentIds
                );

        if (!recommendationItems.isEmpty()) {
            if (preference.coldStart()) {
                sections.add(
                        createSection(
                                "POPULAR",
                                "지금 MOPL에서 인기 있는 콘텐츠",
                                "MOPL에서 인기 있는 콘텐츠예요.",
                                null,
                                null,
                                recommendationItems
                        )
                );
            } else {
                sections.add(
                        createSection(
                                "AI_PERSONALIZED",
                                userName + "님을 위한 AI 추천",
                                "취향과 이용 기록을 바탕으로 추천한 콘텐츠예요.",
                                null,
                                null,
                                recommendationItems
                        )
                );
            }
        }

        LocalDateTime createdAfter =
                LocalDateTime.now(clock).minusDays(NEW_CONTENT_DAYS);

        NewSectionResult newSectionResult =
                findNewSection(
                        preference,
                        createdAfter,
                        usedContentIds
                );

        List<RecommendationSectionItem> newItems =
                takeUnused(
                        newSectionResult.items(),
                        usedContentIds
                );

        if (!newItems.isEmpty()) {
            if (newSectionResult.personalized()) {
                sections.add(
                        createSection(
                                "NEW_FOR_YOU",
                                "새로 들어온 콘텐츠 중 " + userName + "님 취향",
                                "최근 추가된 콘텐츠 중 취향에 맞는 콘텐츠예요.",
                                null,
                                null,
                                newItems
                        )
                );
            } else {
                sections.add(
                        createSection(
                                "NEW_CONTENT",
                                "새로 들어온 콘텐츠",
                                "최근 MOPL에 추가된 콘텐츠예요.",
                                null,
                                null,
                                newItems
                        )
                );
            }
        }

        if (!preference.coldStart()) {
            for (ContentType contentType : PREFERENCE_SECTION_TYPES) {
                addPreferenceSections(
                        sections,
                        usedContentIds,
                        userName,
                        contentType,
                        preference
                );
            }
        }

        return RecommendationSectionsResponse.of(sections);
    }

    private RecommendationSectionsResponse getNewSections(
            String userName,
            RecommendationPreference preference
    ) {
        List<RecommendationSection> sections = new ArrayList<>();
        Set<UUID> genericNewContentIds = new LinkedHashSet<>();

        LocalDateTime createdAfter =
                LocalDateTime.now(clock).minusDays(NEW_CONTENT_DAYS);

        NewSectionResult newSectionResult =
                findNewSection(
                        preference,
                        createdAfter,
                        genericNewContentIds
                );

        List<RecommendationSectionItem> newItems =
                takeUnused(
                        newSectionResult.items(),
                        genericNewContentIds
                );

        if (!newItems.isEmpty()) {
            if (newSectionResult.personalized()) {
                sections.add(
                        createSection(
                                "NEW_FOR_YOU",
                                "새 콘텐츠 중 " + userName + "님 취향",
                                "최근 추가된 콘텐츠 중 취향에 맞는 콘텐츠예요.",
                                null,
                                null,
                                newItems
                        )
                );
            } else {
                sections.add(
                        createSection(
                                "NEW_CONTENT",
                                "새로 추가된 콘텐츠",
                                "최근 MOPL에 추가된 콘텐츠예요.",
                                null,
                                null,
                                newItems
                        )
                );
            }
        }

        // [#98] NEW 탭에서는 전체 신규 섹션과 타입별 신규 섹션 간 중복을 허용한다.
        // 각 타입별 섹션 내부의 contentId 중복만 독립적으로 제거한다.
        addNewTypeSection(
                sections,
                new LinkedHashSet<>(),
                ContentType.MOVIE,
                createdAfter
        );

        addNewTypeSection(
                sections,
                new LinkedHashSet<>(),
                ContentType.TV_SERIES,
                createdAfter
        );

        addNewTypeSection(
                sections,
                new LinkedHashSet<>(),
                ContentType.SPORT,
                createdAfter
        );

        return RecommendationSectionsResponse.of(sections);
    }

    private RecommendationSectionsResponse getTypeSections(
            UUID userId,
            String userName,
            ContentType contentType,
            RecommendationPreference preference
    ) {
        List<RecommendationSection> sections = new ArrayList<>();
        Set<UUID> usedContentIds = new LinkedHashSet<>();

        addTypeRecommendationSection(
                sections,
                usedContentIds,
                userId,
                userName,
                contentType,
                preference
        );

        LocalDateTime createdAfter =
                LocalDateTime.now(clock).minusDays(NEW_CONTENT_DAYS);

        addNewTypeSection(
                sections,
                usedContentIds,
                contentType,
                createdAfter
        );

        if (!preference.coldStart()) {
            addPreferenceSections(
                    sections,
                    usedContentIds,
                    userName,
                    contentType,
                    preference
            );
        }

        return RecommendationSectionsResponse.of(sections);
    }

    private void addTypeRecommendationSection(
            List<RecommendationSection> sections,
            Set<UUID> usedContentIds,
            UUID userId,
            String userName,
            ContentType contentType,
            RecommendationPreference preference
    ) {
        if (preference.coldStart()) {
            List<RecommendationSectionItem> popularItems =
                    recommendationSectionSearchService.findPopularByType(
                            contentType,
                            SECTION_SIZE,
                            Set.copyOf(usedContentIds)
                    );

            List<RecommendationSectionItem> selectedItems =
                    takeUnused(
                            popularItems,
                            usedContentIds
                    );

            if (!selectedItems.isEmpty()) {
                sections.add(
                        createSection(
                                popularKey(contentType),
                                popularTitle(contentType),
                                popularDescription(contentType),
                                contentType,
                                null,
                                selectedItems
                        )
                );
            }

            return;
        }

        List<RecommendationSectionItem> personalizedItems =
                takeUnused(
                        findRecommendationItems(
                                userId,
                                contentType
                        ),
                        usedContentIds
                );

        if (!personalizedItems.isEmpty()) {
            sections.add(
                    createSection(
                            personalizedKey(contentType),
                            personalizedTitle(
                                    userName,
                                    contentType
                            ),
                            personalizedDescription(contentType),
                            contentType,
                            null,
                            personalizedItems
                    )
            );

            return;
        }

        List<RecommendationSectionItem> popularItems =
                recommendationSectionSearchService.findPopularByType(
                        contentType,
                        SECTION_SIZE,
                        Set.copyOf(usedContentIds)
                );

        List<RecommendationSectionItem> selectedItems =
                takeUnused(
                        popularItems,
                        usedContentIds
                );

        if (!selectedItems.isEmpty()) {
            sections.add(
                    createSection(
                            popularKey(contentType),
                            popularTitle(contentType),
                            popularDescription(contentType),
                            contentType,
                            null,
                            selectedItems
                    )
            );
        }
    }

    private void addNewTypeSection(
            List<RecommendationSection> sections,
            Set<UUID> usedContentIds,
            ContentType contentType,
            LocalDateTime createdAfter
    ) {
        List<RecommendationSectionItem> candidates =
                recommendationSectionSearchService.findNewByType(
                        contentType,
                        createdAfter,
                        SECTION_SIZE,
                        Set.copyOf(usedContentIds)
                );

        List<RecommendationSectionItem> selectedItems =
                takeUnused(
                        candidates,
                        usedContentIds
                );

        if (selectedItems.isEmpty()) {
            return;
        }

        sections.add(
                createSection(
                        newKey(contentType),
                        newTitle(contentType),
                        newDescription(contentType),
                        contentType,
                        null,
                        selectedItems
                )
        );
    }

    private void addPreferenceSections(
            List<RecommendationSection> sections,
            Set<UUID> usedContentIds,
            String userName,
            ContentType contentType,
            RecommendationPreference preference
    ) {
        List<RecommendationPreferredTag> preferredTags =
                findPreferredTags(
                        preference,
                        contentType
                );

        int sectionIndex = 1;

        for (RecommendationPreferredTag preferredTag : preferredTags) {
            List<RecommendationSectionItem> candidates =
                    recommendationSectionSearchService
                            .findPopularByPreferenceTag(
                                    contentType,
                                    preferredTag,
                                    SECTION_SIZE,
                                    Set.copyOf(usedContentIds)
                            );

            List<RecommendationSectionItem> selectedItems =
                    takeUnused(
                            candidates,
                            usedContentIds
                    );

            if (selectedItems.isEmpty()) {
                continue;
            }

            sections.add(
                    createSection(
                            preferenceKey(
                                    contentType,
                                    sectionIndex
                            ),
                            preferenceTitle(
                                    userName,
                                    contentType,
                                    preferredTag
                            ),
                            preferenceDescription(contentType),
                            contentType,
                            preferredTag.value(),
                            selectedItems
                    )
            );

            sectionIndex++;
        }
    }

    private NewSectionResult findNewSection(
            RecommendationPreference preference,
            LocalDateTime createdAfter,
            Set<UUID> usedContentIds
    ) {
        if (!preference.coldStart()) {
            List<RecommendationSectionItem> personalizedItems =
                    findNewPreferenceItems(
                            preference,
                            createdAfter,
                            usedContentIds
                    );

            if (!personalizedItems.isEmpty()) {
                return new NewSectionResult(
                        personalizedItems,
                        true
                );
            }
        }

        return new NewSectionResult(
                recommendationSectionSearchService.findNew(
                        createdAfter,
                        SECTION_SIZE,
                        Set.copyOf(usedContentIds)
                ),
                false
        );
    }

    private List<RecommendationSectionItem> findNewPreferenceItems(
            RecommendationPreference preference,
            LocalDateTime createdAfter,
            Set<UUID> usedContentIds
    ) {
        List<RecommendationSectionItem> candidates =
                new ArrayList<>();

        Set<UUID> excludedContentIds =
                Set.copyOf(usedContentIds);

        for (ContentType contentType : PREFERENCE_SECTION_TYPES) {
            List<RecommendationPreferredTag> preferredTags =
                    findPreferredTags(
                            preference,
                            contentType
                    );

            for (RecommendationPreferredTag preferredTag : preferredTags) {
                candidates.addAll(
                        recommendationSectionSearchService
                                .findNewByPreferenceTag(
                                        contentType,
                                        preferredTag,
                                        createdAfter,
                                        SECTION_SIZE,
                                        excludedContentIds
                                )
                );
            }
        }

        Comparator<RecommendationSectionItem> comparator =
                Comparator
                        .comparingLong(
                                RecommendationSectionItem::watcherCount
                        )
                        .reversed()
                        .thenComparing(
                                RecommendationSectionItem::averageRating,
                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        )
                        .thenComparing(
                                Comparator
                                        .comparingLong(
                                                RecommendationSectionItem::reviewCount
                                        )
                                        .reversed()
                        )
                        .thenComparing(
                                RecommendationSectionItem::createdAt,
                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        );

        return candidates.stream()
                .distinct()
                .sorted(comparator)
                .limit(SECTION_SIZE)
                .toList();
    }

    private List<RecommendationSectionItem> findRecommendationItems(
            UUID userId,
            ContentType contentType
    ) {
        List<RecommendationItem> recommendations =
                recommendationService.getRecommendations(userId);

        List<UUID> contentIds =
                recommendations.stream()
                        .filter(recommendation ->
                                contentType == null
                                        || recommendation.type() == contentType
                        )
                        .map(RecommendationItem::contentId)
                        .toList();

        if (contentIds.isEmpty()) {
            return List.of();
        }

        return recommendationSectionSearchService
                .findByContentIds(contentIds);
    }

    private List<RecommendationPreferredTag> findPreferredTags(
            RecommendationPreference preference,
            ContentType contentType
    ) {
        Map<ContentType, List<RecommendationPreferredTag>> preferredTagsByType =
                preference.preferredTagsByType();

        if (preferredTagsByType == null) {
            return List.of();
        }

        return preferredTagsByType
                .getOrDefault(
                        contentType,
                        List.of()
                )
                .stream()
                .limit(MAX_PREFERENCE_SECTIONS_PER_TYPE)
                .toList();
    }

    private List<RecommendationSectionItem> takeUnused(
            List<RecommendationSectionItem> candidates,
            Set<UUID> usedContentIds
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RecommendationSectionItem> selectedItems =
                new ArrayList<>(SECTION_SIZE);

        for (RecommendationSectionItem item : candidates) {
            if (!usedContentIds.add(item.contentId())) {
                continue;
            }

            selectedItems.add(item);

            if (selectedItems.size() >= SECTION_SIZE) {
                break;
            }
        }

        return List.copyOf(selectedItems);
    }

    private RecommendationSection createSection(
            String key,
            String title,
            String description,
            ContentType contentType,
            String tag,
            List<RecommendationSectionItem> items
    ) {
        return new RecommendationSection(
                key,
                title,
                description,
                contentType,
                tag,
                items
        );
    }

    private String personalizedKey(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "PERSONALIZED_MOVIE";
            case TV_SERIES -> "PERSONALIZED_TV_SERIES";
            case SPORT -> "PERSONALIZED_SPORT";
        };
    }

    private String popularKey(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "POPULAR_MOVIE";
            case TV_SERIES -> "POPULAR_TV_SERIES";
            case SPORT -> "POPULAR_SPORT";
        };
    }

    private String newKey(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "NEW_MOVIE";
            case TV_SERIES -> "NEW_TV_SERIES";
            case SPORT -> "NEW_SPORT";
        };
    }

    private String preferenceKey(
            ContentType contentType,
            int sectionIndex
    ) {
        String baseKey =
                switch (contentType) {
                    case MOVIE -> "PREFERENCE_TOP_MOVIE";
                    case TV_SERIES -> "PREFERENCE_TOP_TV_SERIES";
                    case SPORT -> "PREFERENCE_TOP_SPORT";
                };

        if (sectionIndex == 1) {
            return baseKey;
        }

        return baseKey + "_" + sectionIndex;
    }

    private String personalizedTitle(
            String userName,
            ContentType contentType
    ) {
        return switch (contentType) {
            case MOVIE -> userName + "님을 위한 영화 추천";
            case TV_SERIES -> userName + "님을 위한 TV 시리즈 추천";
            case SPORT -> userName + "님을 위한 스포츠 추천";
        };
    }

    private String popularTitle(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "지금 인기 있는 영화";
            case TV_SERIES -> "지금 인기 있는 TV 시리즈";
            case SPORT -> "지금 인기 있는 스포츠 콘텐츠";
        };
    }

    private String newTitle(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "새로 추가된 영화";
            case TV_SERIES -> "새로 추가된 TV 시리즈";
            case SPORT -> "새로 추가된 스포츠";
        };
    }

    private String preferenceTitle(
            String userName,
            ContentType contentType,
            RecommendationPreferredTag preferredTag
    ) {
        String displayTag = displayTagValue(preferredTag);

        return switch (contentType) {
            case MOVIE ->
                    userName
                            + "님이 좋아하는 "
                            + displayTag
                            + " 영화 TOP 10";
            case TV_SERIES ->
                    userName
                            + "님 취향의 "
                            + displayTag
                            + " TV 시리즈 TOP 10";
            case SPORT ->
                    userName
                            + "님이 자주 보는 "
                            + displayTag
                            + " 콘텐츠 TOP 10";
        };
    }

    private String displayTagValue(
            RecommendationPreferredTag preferredTag
    ) {
        if (!"GENRE".equals(preferredTag.tag())) {
            return preferredTag.value();
        }

        return switch (preferredTag.value()) {
            case "ACTION" -> "액션";
            case "ADVENTURE" -> "모험";
            case "ANIMATION" -> "애니메이션";
            case "COMEDY" -> "코미디";
            case "CRIME" -> "범죄";
            case "DOCUMENTARY" -> "다큐멘터리";
            case "DRAMA" -> "드라마";
            case "FAMILY" -> "가족";
            case "FANTASY" -> "판타지";
            case "HISTORY" -> "역사";
            case "HORROR" -> "공포";
            case "MUSIC" -> "음악";
            case "MYSTERY" -> "미스터리";
            case "ROMANCE" -> "로맨스";
            case "SF", "SCI_FI" -> "SF";
            case "TV_MOVIE" -> "TV 영화";
            case "THRILLER" -> "스릴러";
            case "WAR" -> "전쟁";
            case "WESTERN" -> "서부";
            case "KIDS" -> "키즈";
            case "NEWS" -> "뉴스";
            case "REALITY" -> "리얼리티";
            case "SOAP" -> "솝 오페라";
            case "TALK" -> "토크";
            case "POLITICS" -> "정치";
            default -> preferredTag.value();
        };
    }

    private String personalizedDescription(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "취향과 이용 기록을 바탕으로 추천한 영화예요.";
            case TV_SERIES -> "취향과 이용 기록을 바탕으로 추천한 TV 시리즈예요.";
            case SPORT -> "취향과 이용 기록을 바탕으로 추천한 스포츠 콘텐츠예요.";
        };
    }

    private String popularDescription(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "MOPL에서 인기 있는 영화예요.";
            case TV_SERIES -> "MOPL에서 인기 있는 TV 시리즈예요.";
            case SPORT -> "MOPL에서 인기 있는 스포츠 콘텐츠예요.";
        };
    }

    private String newDescription(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "최근 MOPL에 추가된 영화예요.";
            case TV_SERIES -> "최근 MOPL에 추가된 TV 시리즈예요.";
            case SPORT -> "최근 MOPL에 추가된 스포츠 콘텐츠예요.";
        };
    }

    private String preferenceDescription(ContentType contentType) {
        return switch (contentType) {
            case MOVIE -> "선호 태그를 바탕으로 추천한 영화예요.";
            case TV_SERIES -> "선호 태그를 바탕으로 추천한 TV 시리즈예요.";
            case SPORT -> "선호 태그를 바탕으로 추천한 스포츠 콘텐츠예요.";
        };
    }

    private record NewSectionResult(
            List<RecommendationSectionItem> items,
            boolean personalized
    ) {
    }
}
