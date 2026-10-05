package com.mopl.realtime.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.support.MessageReviewTestSettings;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProfanityRuleMaskLengthTest {
    private final ProfanityRule rule = new ProfanityRule(
        ModerationTestSettings.defaults(), MessageReviewTestSettings.defaults());

    static Stream<Arguments> maskedMessages() {
        return Stream.of(
            Arguments.of("병신", "**"),
            Arguments.of("개새끼", "***"),
            Arguments.of("씨 발", "**"),
            Arguments.of("씨@발", "**"),
            Arguments.of("씨\u200b발", "**"),
            Arguments.of("씨\n발", "**"),
            Arguments.of("씨💢발", "**"),
            Arguments.of("씨씨씨발발", "*****"),
            Arguments.of("개 새 끼", "***"),
            Arguments.of("ㅅ ㅂ", "**"),
            Arguments.of("씨발시발", "****"),
            Arguments.of("😀 너는 병신! 씨 발, ＡＢＣ", "😀 너는 **! **, ＡＢＣ"),
            Arguments.of("안녕 씨발! 또 만나요", "안녕 **! 또 만나요"),
            Arguments.of("개새끼풀", "***풀")
        );
    }

    @ParameterizedTest
    @MethodSource("maskedMessages")
    void masksEachDetectedCharacterWithoutCountingEvasionSeparators(String message, String expected) {
        var decision = rule.inspect(message);
        assertThat(decision.action()).isEqualTo(RuleAction.MASK);
        assertThat(decision.content()).isEqualTo(expected);
    }

    @Test
    void preservesAllowedAndReviewedMessages() {
        assertThat(rule.inspect("시발점부터 살펴보자").action()).isEqualTo(RuleAction.ALLOW);
        assertThat(rule.inspect("시발점부터 살펴보자").content()).isEqualTo("시발점부터 살펴보자");
        assertThat(rule.inspect("꺼져").action()).isEqualTo(RuleAction.REVIEW);
        assertThat(rule.inspect("꺼져").content()).isEqualTo("꺼져");
    }
}
