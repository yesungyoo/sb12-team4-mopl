package com.mopl.realtime.moderation.review;

import com.mopl.realtime.moderation.dto.MessageReviewContext;
import com.mopl.realtime.moderation.dto.RuleAction;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 명확한 정보 질문만 Message Review에서 제외하고, 나머지는 문맥 검토 대상으로 유지한다. */
@Component
public class MessageReviewRoutingFilter {
    private static final Pattern INFORMATION_REQUEST = Pattern.compile(
        "(?:몇\\s*시|러닝타임|어디서\\s*볼\\s*수|원작|누군지|누구)"
    );
    private static final Pattern QUESTION_ENDING = Pattern.compile(
        "(?:[?？]|(?:인가요|인가|있나요|있어|돼요|돼|나요|까요|누군지|사람)\\s*[?？]?)$"
    );
    private static final Pattern INTERPERSONAL_OR_HOSTILE_SIGNAL = Pattern.compile(
        "(?:너|네가|니가|당신|쟤|걔|부모|가족|집안|팬들|팬덤|수준|지능|인격|인성|가정교육|"
            + "멍청|한심|못하|꺼져|죽|사라|퇴출|말\\s*걸지|별로|싫|아쉽|답답|재미없|비웃|조롱|ㅋㅋ|ㅎㅎ|noob)"
    );

    public boolean shouldRoute(RuleAction ruleAction, MessageReviewContext context) {
        return exclusionReason(ruleAction, context).isEmpty();
    }

    public Optional<String> exclusionReason(RuleAction ruleAction, MessageReviewContext context) {
        if (ruleAction == RuleAction.MASK) return Optional.of("rule_mask");
        if (ruleAction == RuleAction.REVIEW) return Optional.empty();
        if (isClearlyNormalInformationQuestion(context)) return Optional.of("clear_information_question_without_context");
        return Optional.empty();
    }

    private boolean isClearlyNormalInformationQuestion(MessageReviewContext context) {
        if (!context.recentConversation().isEmpty()) return false;
        String message = Normalizer.normalize(context.currentMessage(), Normalizer.Form.NFKC)
            .strip().toLowerCase(Locale.ROOT);
        return INFORMATION_REQUEST.matcher(message).find()
            && QUESTION_ENDING.matcher(message).find()
            && !INTERPERSONAL_OR_HOSTILE_SIGNAL.matcher(message).find();
    }
}
