package com.mopl.realtime.moderation.service;

import com.mopl.core.common.enums.DetectionSource;
import com.mopl.core.common.enums.MessageType;
import com.mopl.core.common.enums.ModerationAction;
import com.mopl.core.common.enums.ModerationCategory;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.dto.RuleDecision;
import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.exception.ModerationException;
import com.mopl.realtime.moderation.repository.ChatRestrictionStore;
import com.mopl.realtime.moderation.review.MessageReviewService;
import com.mopl.realtime.moderation.review.ModerationReviewDispatcher;
import com.mopl.realtime.moderation.rule.ProfanityRule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageModerationService {
    private static final Logger log = LoggerFactory.getLogger(MessageModerationService.class);
    private final ChatRestrictionStore restrictions;
    private final ProfanityRule rule;
    private final MessageReviewService messageReviews;
    private final ModerationLogService logs;
    private final ModerationReviewDispatcher reviews;
    private final ModerationProperties properties;
    private final MeterRegistry meters;

    public void assertCanSend(UUID userId) {
        meters.counter("moderation.message.received").increment();
        final ChatRestrictionStore.Restriction restriction;
        try {
            restriction = restrictions.restriction(userId);
        } catch (RuntimeException exception) {
            log.warn("Redis 채팅 제한 상태 조회 실패. 전송을 차단합니다.", exception);
            meters.counter("moderation.messages", "outcome", "restriction_check_error").increment();
            throw new ModerationException(ModerationException.ErrorCode.CHAT_RESTRICTION_CHECK_FAILED, exception);
        }
        if (restriction != null) {
            meters.counter("moderation.messages", "outcome", "restricted").increment();
            throw new ModerationException(ModerationException.ErrorCode.CHAT_RESTRICTED, restriction);
        }
    }

    public RuleDecision inspect(UUID userId, MessageType type, UUID targetId, String text) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            var decision = rule.inspect(text);
            meters.counter("moderation.rule.decisions", "action", decision.action().name().toLowerCase(Locale.ROOT)).increment();
            if (decision.action() == RuleAction.ALLOW) {
                outcome = "allowed";
                return decision;
            }
            if (decision.action() == RuleAction.MASK) {
                violation(userId, type, targetId, text, DetectionSource.RULE,
                    ModerationCategory.PROFANITY, ModerationAction.MASK);
                outcome = "masked";
                return decision;
            }
            outcome = "review_forwarded";
            return decision;
        } finally {
            meters.counter("moderation.messages", "outcome", outcome).increment();
            sample.stop(meters.timer("moderation.message", "outcome", outcome));
        }
    }

    public void reviewAfterCommit(UUID userId, MessageType type, UUID targetId, UUID messageId, String original,
        RuleAction action) {
        messageReviews.reviewAfterCommit(userId, type, targetId, messageId, original, action);
    }

    private void violation(UUID userId, MessageType type, UUID targetId, String original,
        DetectionSource source, ModerationCategory category, ModerationAction action) {
        // 증거 원문은 전용 테이블과 제재 심사에서만 사용하고, 전송 내용과 구분한다.
        logs.record(userId, type, targetId, original, source, category, action);
        meters.counter("moderation.violations", "source", source.name().toLowerCase(Locale.ROOT),
            "action", action.name().toLowerCase(Locale.ROOT)).increment();
        if (logs.recentCount(userId) >= properties.violationThreshold()) {
            try { reviews.submit(userId, type, targetId, original); }
            catch (RuntimeException exception) {
                // 제재 심사 예약 실패가 이미 확정한 메시지 판별 결과를 변경하지 않도록 한다.
                meters.counter("moderation.reviews", "outcome", "dispatch_error").increment();
            }
        }
    }
}
