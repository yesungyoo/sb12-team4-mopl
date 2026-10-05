package com.mopl.realtime.moderation.review;

import com.mopl.realtime.moderation.dto.SanctionLevel;
import org.springframework.ai.tool.annotation.Tool;

/** 도구는 결정만 제출하며, 대상 사용자와 토큰 관리 및 Redis 적용은 서버가 담당한다. */
public class SanctionTool {
    private final SanctionDecisionInbox decisions;
    private final ReviewObservation observation;

    SanctionTool(SanctionDecisionInbox decisions, ReviewObservation observation) {
        this.decisions = decisions; this.observation = observation;
    }

    @Tool(description = "심사 대상 사용자의 제재 결정을 서버에 제출합니다. NONE, TEMPORARY_SHORT(10분), TEMPORARY_LONG(1일) 중 하나와 200자 이내의 간단한 사유를 전달하세요. 반환값은 결정 접수 여부이며 실제 제한 적용 여부가 아닙니다.", returnDirect = true)
    public boolean applyChatRestriction(SanctionLevel level, String reason) {
        observation.event("tool_entered", "level=" + level + " decisionState=" + decisions.state());
        boolean accepted = decisions.submit(level, reason);
        observation.event("tool_returning", "decisionAccepted=" + accepted + " decisionState=" + decisions.state());
        return accepted;
    }
}
