package com.mopl.realtime.moderation.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mopl.core.common.enums.*;
import com.mopl.realtime.moderation.dto.*;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore;
import com.mopl.realtime.moderation.review.MessageReviewService;
import com.mopl.realtime.moderation.review.ModerationReviewDispatcher;
import com.mopl.realtime.moderation.rule.ProfanityRule;
import com.mopl.realtime.moderation.support.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
@ExtendWith(MockitoExtension.class)
class MessageModerationServiceTest {
    @Mock ChatRestrictionStore restrictions;
    @Mock ModerationLogService logs;
    @Mock ModerationReviewDispatcher reviews;
    @Mock MessageReviewService messageReviews;
    private MessageModerationService service;
    private SimpleMeterRegistry meters;
    private final UUID user = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    @BeforeEach void setup() {
        var settings = ModerationTestSettings.defaults();
        meters = new SimpleMeterRegistry();
        service = new MessageModerationService(restrictions, new ProfanityRule(settings, MessageReviewTestSettings.defaults()),
            messageReviews, logs, reviews, settings, meters);
    }
    @Test void normalMessageSkipsLogsAndBothAiRoles() {
        service.assertCanSend(user);
        assertThat(service.inspect(user, MessageType.DM, target, "안녕하세요").content()).isEqualTo("안녕하세요");
        verifyNoInteractions(logs, reviews, messageReviews);
        assertThat(meters.get("moderation.message.received").counter().count()).isEqualTo(1);
    }
    @Test void ruleMaskPersistsRawEvidenceAndSkipsMessageAi() {
        when(logs.recentCount(user)).thenReturn(2L);
        assertThat(service.inspect(user, MessageType.DM, target, "너는 병신!").content()).isEqualTo("너는 **!");
        verify(logs).record(user, MessageType.DM, target, "너는 병신!", DetectionSource.RULE,
            ModerationCategory.PROFANITY, ModerationAction.MASK);
        verifyNoInteractions(reviews, messageReviews);
    }
    @ParameterizedTest @EnumSource(MessageType.class)
    void thresholdSchedulesExistingSanctionReviewerInBothChannels(MessageType type) {
        when(logs.recentCount(user)).thenReturn(3L);
        assertThat(service.inspect(user, type, target, "씨발").content()).isEqualTo("**");
        verify(reviews).submit(user, type, target, "씨발");
    }
    @Test void reviewReturnsOriginalAndNeverWaitsForMessageAi() {
        var decision = service.inspect(user, MessageType.DM, target, "꺼져");
        assertThat(decision.action()).isEqualTo(RuleAction.REVIEW);
        assertThat(decision.content()).isEqualTo("꺼져");
        verifyNoInteractions(logs, reviews, messageReviews);
        assertThat(meters.get("moderation.messages").tag("outcome", "review_forwarded").counter().count()).isEqualTo(1);
    }
    @Test void dispatchFailureKeepsConfirmedMask() {
        when(logs.recentCount(user)).thenReturn(3L);
        doThrow(new IllegalStateException("unavailable")).when(reviews).submit(user,MessageType.DM,target,"씨발");
        assertThat(service.inspect(user,MessageType.DM,target,"씨발").content()).isEqualTo("**");
        assertThat(meters.get("moderation.reviews").tag("outcome","dispatch_error").counter().count()).isEqualTo(1);
    }
    @Test void restrictionBlocksBeforeRuleOrLogWork() {
        when(restrictions.restriction(user)).thenReturn(new ChatRestrictionStore.Restriction(SanctionLevel.TEMPORARY_SHORT, java.time.Instant.now().plusSeconds(600)));
        assertThatThrownBy(() -> service.assertCanSend(user)).isInstanceOf(ModerationException.class)
            .extracting(e -> ((ModerationException)e).getErrorCode()).isEqualTo(ModerationException.ErrorCode.CHAT_RESTRICTED);
        verifyNoInteractions(logs,reviews,messageReviews);
    }
    @Test void restrictionLookupFailureUsesRetryableErrorAndKeepsFailClosedPolicy() {
        var redisFailure = new IllegalStateException("redis unavailable");
        when(restrictions.restriction(user)).thenThrow(redisFailure);
        assertThatThrownBy(() -> service.assertCanSend(user))
            .isInstanceOf(ModerationException.class)
            .satisfies(exception -> {
                var moderationException = (ModerationException) exception;
                assertThat(moderationException.getErrorCode()).isEqualTo(ModerationException.ErrorCode.CHAT_RESTRICTION_CHECK_FAILED);
                assertThat(moderationException.getCause()).isSameAs(redisFailure);
                assertThat(moderationException.getErrorCode().getStatus()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(moderationException.getRestriction()).isNull();
            });
        verifyNoInteractions(logs,reviews,messageReviews);
        assertThat(meters.get("moderation.messages").tag("outcome", "restriction_check_error").counter().count()).isEqualTo(1);
    }
}
