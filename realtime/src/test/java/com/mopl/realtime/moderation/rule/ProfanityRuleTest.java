package com.mopl.realtime.moderation.rule;
import static org.assertj.core.api.Assertions.assertThat;
import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import com.mopl.realtime.moderation.support.MessageReviewTestSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
class ProfanityRuleTest {
    private final ProfanityRule rule = new ProfanityRule(ModerationTestSettings.defaults(), MessageReviewTestSettings.defaults());
    @ParameterizedTest
    @ValueSource(strings = {"안녕하세요", "오늘 영화 재밌네요", "시발점부터 살펴보자", "부산 여행"})
    void ordinaryMessagesAreAllowed(String text) {
        assertThat(rule.inspect(text).action()).isEqualTo(RuleAction.ALLOW);
        assertThat(rule.inspect(text).content()).isEqualTo(text);
    }
    @ParameterizedTest
    @CsvSource(value = {"씨발|**", "씨 발|**", "씨@발|**", "씨씨씨발발|*****", "개 새 끼|***", "개새끼풀|***풀", "ㅅ ㅂ|**", "씨\u200b발|**"}, delimiter = '|')
    void clearProfanityAndBasicEvasionAreMasked(String text, String expected) {
        assertThat(rule.inspect(text).action()).isEqualTo(RuleAction.MASK);
        assertThat(rule.inspect(text).content()).isEqualTo(expected);
    }
    @Test void preservesOriginalOutsideMatchedRangesAndNormalizesOnlyForDetection() {
        assertThat(rule.inspect("😀 너는 병신! 씨 발, ＡＢＣ").content()).isEqualTo("😀 너는 **! **, ＡＢＣ");
        assertThat(rule.inspect("안녕 씨발! 또 만나요").content()).isEqualTo("안녕 **! 또 만나요");
    }
    @ParameterizedTest @ValueSource(strings = {"꺼져", "너는 멍청이!", "죽어", "병신년 역사"})
    void contextDependentExpressionsAreReviewed(String text) {
        assertThat(rule.inspect(text).action()).isEqualTo(RuleAction.REVIEW);
        assertThat(rule.inspect(text).content()).isEqualTo(text);
    }
    @Test void clearProfanityTakesPrecedenceOverContextReview() {
        assertThat(rule.inspect("꺼져 씨발").action()).isEqualTo(RuleAction.MASK);
    }
}
