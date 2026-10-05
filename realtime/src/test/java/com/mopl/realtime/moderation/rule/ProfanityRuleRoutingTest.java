package com.mopl.realtime.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.config.MessageReviewProperties;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.support.MessageReviewTestSettings;
import com.mopl.realtime.moderation.support.ModerationTestSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfanityRuleRoutingTest {
    private final ProfanityRule rule = new ProfanityRule(
        ModerationTestSettings.defaults(), MessageReviewTestSettings.defaults());

    @ParameterizedTest
    @CsvSource(value = {
        "시발|**", "병신|**", "좆|*", "ㅈ같아|**아", "ㅈ같네|**네", "ㅈ같다|**다", "ㅈ까|**",
        "니애미|***", "니 애미|***", "니애비|***", "니 애비|***", "느금마|***",
        "ㅆㅂ|**", "ㅆ ㅂ|**", "ㅆ.ㅂ|**", "씨 발|**", "씨.발|**", "개새끼풀|***풀",
        "좆같아|*같아", "좆같네|*같네", "좆됐다|*됐다", "좆되네|*되네",
        "너 병 신이냐?|너 **이냐?", "너 병-신이냐?|너 **이냐?", "너 병💢신이냐?|너 **이냐?",
        "병신년아!|**년아!", "병신년(丙申年)아!|**년(丙申年)아!",
        "시발점 씨발|시발점 **", "시발역 병신|시발역 **",
        "병신년(丙申年) 씨발|병신년(丙申年) **", "병신년 씨발|병신년 **",
        "새끼야 씨발|새끼야 **", "시 발점|**점", "시시발점|***점",
        "니니💢애애미미|******", "😀씨 발|😀**"
    }, delimiter = '|')
    void confirmedProfanityIsMaskedWithoutSuppressingOtherMatches(String message, String masked) {
        var result = rule.inspect(message);
        assertThat(result.action()).isEqualTo(RuleAction.MASK);
        assertThat(result.content()).isEqualTo(masked);
    }

    @ParameterizedTest
    @ValueSource(strings = {"너 병\n신이냐?", "너 병\u200b신이냐?"})
    void separatedWeakInflectionPreservesNewlineAndZeroWidthEvasionDetection(String message) {
        var result = rule.inspect(message);
        assertThat(result.action()).isEqualTo(RuleAction.MASK);
        assertThat(result.content()).isEqualTo("너 **이냐?");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "시발점부터 살펴보자", "시발역에서 열차를 탔다.", "시발주자가 첫 구간을 달린다.",
        "시발택시의 역사 자료를 찾아보자.", "시발항에서 배가 출발했다.",
        "퍼시발이라는 등장인물의 이름을 읽었다.", "시발점부터 살펴보자",
        "2016년 병신년(丙申年)의 달력을 살펴보자.", "병신년(丙申年)은 원숭이해를 가리킨다.",
        "병신년（丙申年）", "병신년 丙申年", "당시 발행된 신문을 찾아보자.",
        "오늘 ㅈㄴ 웃기네", "개웃기다", "개맛있다", "존나 웃기다", "존나 맛있다",
        "새끼 강아지가 귀엽다", "동물의 새끼를 보호하고 있다", "고양이가 새끼를 낳았다",
        "𐐀씨 발", "1씨 발", "a\u0301씨 발"
    })
    void literalLexicalRangesAndNormalControlsAreAllowed(String message) {
        var result = rule.inspect(message);
        assertThat(result.action()).isEqualTo(RuleAction.ALLOW);
        assertThat(result.content()).isEqualTo(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "병신년 역사", "병신년", "병신년이 왔다.", "2016년 병신년ㅋㅋ 원숭이해네.",
        "야 이 새끼야", "이 새끼 뭐야", "새끼야 지금 뭐 하냐", "그 새끼", "저 새끼",
        "시발역 같은 놈.", "시발점 같은 새끼야.", "시발역 꺼져", "병신년 (다른 설명) 丙申年"
    })
    void contextualCandidatesAreReviewedWithoutSemanticInference(String message) {
        var result = rule.inspect(message);
        assertThat(result.action()).isEqualTo(RuleAction.REVIEW);
        assertThat(result.content()).isEqualTo(message);
    }

    @Test
    void lexicalResourceNormalizesLiteralSurfaces() {
        assertThat(ProfanityRule.loadReviewedLexicalSurfaces(input("# reviewed\n시발점\n시발역\n")))
            .containsExactly("시발점", "시발역");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# comment only", "시발", "병신", "병신년", "시 발점", "정상어", "시발점\n시발점", "시발점\n시발점"})
    void invalidLexicalResourcesFailAtStartup(String contents) {
        assertThatIllegalStateException().isThrownBy(() -> ProfanityRule.loadReviewedLexicalSurfaces(input(contents)));
    }

    @Test
    void missingLexicalResourceFailsAtStartup() {
        assertThatIllegalStateException().isThrownBy(() -> ProfanityRule.loadReviewedLexicalSurfaces(null));
    }

    @Test
    void lexicalRangesNeverSuppressConfiguredStrongOrReviewExpressions() {
        var defaults = ModerationTestSettings.defaults();
        var properties = new ModerationProperties(defaults.violationWindow(), defaults.violationThreshold(),
            defaults.contextLimit(), defaults.reviewTimeout(), defaults.applyTimeout(), defaults.reviewCooldown(),
            defaults.shortRestriction(), defaults.longRestriction(), defaults.workers(), defaults.queueCapacity(),
            List.of("시발점"));
        var strongRule = new ProfanityRule(properties, MessageReviewTestSettings.defaults());
        assertThat(strongRule.inspect("시발점").action()).isEqualTo(RuleAction.MASK);
        assertThat(strongRule.inspect("시발점").content()).isEqualTo("***");

        var reviewDefaults = MessageReviewTestSettings.defaults();
        var reviewProperties = new MessageReviewProperties(reviewDefaults.timeout(), reviewDefaults.workers(),
            reviewDefaults.queueCapacity(), List.of("시발점"));
        var reviewRule = new ProfanityRule(defaults, reviewProperties);
        assertThat(reviewRule.inspect("시발점").action()).isEqualTo(RuleAction.REVIEW);
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
