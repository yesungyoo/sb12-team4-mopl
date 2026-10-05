package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.realtime.moderation.dto.MessageReviewContext;
import com.mopl.realtime.moderation.dto.RuleAction;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageReviewRoutingFilterTest {
    private final MessageReviewRoutingFilter filter = new MessageReviewRoutingFilter();

    @Test void reviewAlwaysRoutesEvenWhenTextLooksLikeAnInformationQuestion() {
        assertThat(filter.shouldRoute(RuleAction.REVIEW, context("오늘 경기 몇 시에 시작해?", List.of()))).isTrue();
    }

    @Test void clearlyNormalInformationQuestionWithoutContextCanBeSkipped() {
        assertThat(filter.shouldRoute(RuleAction.ALLOW, context("오늘 경기 몇 시에 시작해?", List.of()))).isFalse();
    }

    @Test void informationQuestionWithConversationContextStillRoutes() {
        assertThat(filter.shouldRoute(RuleAction.ALLOW,
            context("오늘 경기 몇 시에 시작해?", List.of("오늘 선수 경기력 진짜 별로더라")))).isTrue();
    }

    @Test void indirectPersonalAttackCandidateRoutes() {
        assertThat(filter.shouldRoute(RuleAction.ALLOW,
            context("아 네 취향 보면 이해는 간다", List.of("그 배우 연기 별로야")))).isTrue();
    }

    @Test void ambiguousAllowMessageRoutes() {
        assertThat(filter.shouldRoute(RuleAction.ALLOW, context("그 사람은 좀 그렇네", List.of()))).isTrue();
    }

    @Test void maskNeverRoutesToMessageReview() {
        assertThat(filter.shouldRoute(RuleAction.MASK,
            context("오늘 경기 몇 시에 시작해?", List.of("상대 문맥")))).isFalse();
    }

    private static MessageReviewContext context(String current, List<String> recent) {
        return new MessageReviewContext(current, recent.stream()
            .map(text -> new MessageReviewContext.ConversationMessage(false, text)).toList());
    }
}
