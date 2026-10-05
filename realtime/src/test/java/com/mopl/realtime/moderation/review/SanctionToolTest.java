package com.mopl.realtime.moderation.review;

import static org.assertj.core.api.Assertions.*;

import com.mopl.realtime.moderation.dto.SanctionLevel;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SanctionToolTest {
    @ParameterizedTest @EnumSource(SanctionLevel.class)
    void submitsEachAllowedLevelOnlyOnce(SanctionLevel level) throws Exception {
        var deadline = Instant.now().plusSeconds(30);
        var observation = new ReviewObservation(deadline);
        var inbox = new SanctionDecisionInbox(deadline, observation);
        var tool = new SanctionTool(inbox, observation);
        assertThat(tool.applyChatRestriction(level, "반복 위반")).isTrue();
        assertThat(tool.applyChatRestriction(level, "반복 위반")).isFalse();
        assertThat(inbox.await().level()).isEqualTo(level);
        assertThat(inbox.state()).isEqualTo(SanctionDecisionInbox.State.ACCEPTED);
    }
    @Test void timedOutOrClosedReviewCannotSubmit() {
        var past = Instant.now().minusSeconds(1);
        var observation = new ReviewObservation(past);
        var expired = new SanctionDecisionInbox(past, observation);
        assertThat(new SanctionTool(expired, observation).applyChatRestriction(SanctionLevel.TEMPORARY_LONG, "사유")).isFalse();
        assertThat(expired.state()).isEqualTo(SanctionDecisionInbox.State.EXPIRED);
        var future = Instant.now().plusSeconds(30);
        var closed = new SanctionDecisionInbox(future, new ReviewObservation(future));
        closed.close();
        assertThat(new SanctionTool(closed, observation).applyChatRestriction(SanctionLevel.TEMPORARY_SHORT, "사유")).isFalse();
    }
    @Test void invalidDecisionDoesNotConsumeAcceptance() throws Exception {
        var deadline = Instant.now().plusSeconds(30);
        var observation = new ReviewObservation(deadline);
        var inbox = new SanctionDecisionInbox(deadline, observation);
        var tool = new SanctionTool(inbox, observation);
        assertThatThrownBy(() -> tool.applyChatRestriction(null, "사유")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.applyChatRestriction(SanctionLevel.NONE, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.applyChatRestriction(SanctionLevel.NONE, "x".repeat(201)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(inbox.state()).isEqualTo(SanctionDecisionInbox.State.WAITING);
        assertThat(tool.applyChatRestriction(SanctionLevel.NONE, "정상 문맥")).isTrue();
        assertThat(inbox.await().level()).isEqualTo(SanctionLevel.NONE);
    }
}
