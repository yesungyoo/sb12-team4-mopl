package com.mopl.realtime.moderation.rule;

import com.mopl.realtime.moderation.config.MessageReviewProperties;
import com.mopl.realtime.moderation.config.ModerationProperties;
import com.mopl.realtime.moderation.dto.RuleAction;
import com.mopl.realtime.moderation.dto.RuleDecision;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProfanityRule {
	public static final String MASKED_MESSAGE = "[메시지가 가려졌습니다]";
	private static final String SEPARATOR = "[\\p{Z}\\p{P}\\p{S}\\p{Cf}\\s]";
	private static final Pattern SEPARATOR_PATTERN = Pattern.compile(SEPARATOR);
	private static final Set<String> WEAK_STEMS = Set.of("시발", "병신", "좆");
	private static final String LEXICAL_RESOURCE = "/moderation/reviewed-lexical-surfaces.txt";
	private static final String WORD_START = "(?<![\\p{L}\\p{N}\\p{M}])";
	private static final Pattern PERSON_VOCATIVE_PATTERN = Pattern.compile(
		WORD_START + "새끼야(?=$|" + SEPARATOR + ")");
	private static final Pattern PERSON_DEMONSTRATIVE_PATTERN = Pattern.compile(
		WORD_START + "(?:이|그|저)[\\p{Z}\\s]+새끼");
	private static final Pattern LEXICAL_COMPARISON_PERSON_REFERENCE = Pattern.compile(
		"[\\p{Z}\\s]+같은[\\p{Z}\\s]+(?:놈|새끼)");
	private static final Pattern LOCAL_CALENDAR_HANJA = Pattern.compile(
		"[\\p{Zs}\\t]*(?:\\([\\p{Zs}\\t]*丙申年[\\p{Zs}\\t]*\\)|丙申年)");
	private static final Pattern CALENDAR_INSULT_VOCATIVE = Pattern.compile(
		"아(?=$|" + SEPARATOR + ")");

	private enum ExpressionKind { STRONG, WEAK }
	private record ExpressionPattern(String stem, ExpressionKind kind, Pattern pattern) {}
	private record MatchCandidate(ExpressionPattern expression, int start, int end, boolean separated) {}

	private final List<ExpressionPattern> profanityPatterns;
	private final List<Pattern> reviewPatterns;
	private final List<String> reviewedLexicalSurfaces;

	public ProfanityRule(ModerationProperties properties, MessageReviewProperties messageProperties) {
		profanityPatterns = properties.prohibitedWords().stream().map(word -> {
			String stem = Normalizer.normalize(word, Normalizer.Form.NFKC);
			ExpressionKind kind = WEAK_STEMS.contains(stem) ? ExpressionKind.WEAK : ExpressionKind.STRONG;
			return new ExpressionPattern(stem, kind, pattern(stem));
		}).toList();
		reviewPatterns = messageProperties.reviewExpressions().stream().map(ProfanityRule::pattern).toList();
		reviewedLexicalSurfaces = loadReviewedLexicalSurfaces(
			ProfanityRule.class.getResourceAsStream(LEXICAL_RESOURCE));
	}

	static List<String> loadReviewedLexicalSurfaces(InputStream input) {
		if (input == null) throw new IllegalStateException("Missing reviewed lexical surface resource");
		Set<String> surfaces = new LinkedHashSet<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String surface = Normalizer.normalize(line.strip(), Normalizer.Form.NFKC);
				if (surface.isEmpty() || surface.startsWith("#")) continue;
				if (SEPARATOR_PATTERN.matcher(surface).find()
					|| WEAK_STEMS.contains(surface)
					|| surface.contains("병신년")
					|| WEAK_STEMS.stream().noneMatch(surface::contains)) {
					throw new IllegalStateException("Invalid reviewed lexical surface: " + surface);
				}
				if (!surfaces.add(surface)) {
					throw new IllegalStateException("Duplicate reviewed lexical surface: " + surface);
				}
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot read reviewed lexical surfaces", exception);
		}
		if (surfaces.isEmpty()) throw new IllegalStateException("Empty reviewed lexical surface resource");
		return List.copyOf(surfaces);
	}

	private static Pattern pattern(String word) {
		StringBuilder regex = new StringBuilder();
		String normalized = Normalizer.normalize(word, Normalizer.Form.NFKC);
		normalized.codePoints().forEach(cp -> {
			if (!regex.isEmpty()) regex.append(SEPARATOR).append('*');
			regex.append(Pattern.quote(new String(Character.toChars(cp)))).append('+');
		});
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	public RuleDecision inspect(String text) {
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
		List<MatchCandidate> candidates = findMatches(normalized);
		List<int[]> lexicalRanges = allowedRanges(normalized);
		boolean[] lexicalCovered = new boolean[normalized.length()];
		for (int[] range : lexicalRanges) Arrays.fill(lexicalCovered, range[0], range[1], true);
		List<int[]> masks = new ArrayList<>();
		boolean needsReview = reviewPatterns.stream().anyMatch(p -> p.matcher(normalized).find());
		for (MatchCandidate candidate : candidates) {
			if (!isValidSeparatedMatch(normalized, candidate)) continue;
			RuleAction action = candidate.expression().kind() == ExpressionKind.STRONG
				? RuleAction.MASK : classifyWeakMatch(normalized, candidate, lexicalCovered);
			if (action == RuleAction.MASK) masks.add(new int[] {candidate.start(), candidate.end()});
			if (action == RuleAction.REVIEW) needsReview = true;
		}
		needsReview |= hasContextualCandidate(normalized, lexicalRanges);
		if (!masks.isEmpty()) return new RuleDecision(RuleAction.MASK, mask(text, normalized, masks));
		if (needsReview) return new RuleDecision(RuleAction.REVIEW, text);
		return new RuleDecision(RuleAction.ALLOW, text);
	}

	private List<MatchCandidate> findMatches(String normalized) {
		List<MatchCandidate> candidates = new ArrayList<>();
		for (ExpressionPattern expression : profanityPatterns) {
			Matcher matcher = expression.pattern().matcher(normalized);
			while (matcher.find()) {
				boolean separated = SEPARATOR_PATTERN.matcher(matcher.group()).find();
				candidates.add(new MatchCandidate(expression, matcher.start(), matcher.end(), separated));
			}
		}
		return candidates;
	}

	private static boolean isValidSeparatedMatch(String normalized, MatchCandidate candidate) {
		// 왼쪽 경계만 검사한다. 뒤 활용을 제한하지 않아 병 신이냐 같은 우회를 유지한다.
		// 씨 발라 오탐과 너씨 발 미탐은 이 최소 경계 정책의 알려진 한계다.
		return !candidate.separated() || candidate.start() == 0
			|| !isWordCharacter(normalized.codePointBefore(candidate.start()));
	}

	private static boolean isWordCharacter(int cp) {
		return Character.isLetterOrDigit(cp) || switch (Character.getType(cp)) {
			case Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.NON_SPACING_MARK,
				Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true;
			default -> false;
		};
	}

	private List<int[]> allowedRanges(String normalized) {
		List<int[]> ranges = new ArrayList<>();
		// 정상 surface에는 욕설용 separator/repetition regex를 사용하지 않는다.
		for (String surface : reviewedLexicalSurfaces) {
			int from = 0;
			for (int start; (start = normalized.indexOf(surface, from)) >= 0; from = start + 1) {
				ranges.add(new int[] {start, start + surface.length()});
			}
		}
		return ranges;
	}

	private static RuleAction classifyWeakMatch(String normalized, MatchCandidate candidate, boolean[] lexicalCovered) {
		// 반복/분리 입력을 정상 역법 표현으로 복원하지 않는다.
		if (candidate.expression().stem().equals("병신")
			&& candidate.end() == candidate.start() + "병신".length()
			&& normalized.startsWith("병신년", candidate.start())) {
			return classifyCalendarYear(normalized, candidate.start() + "병신년".length());
		}
		return isCovered(lexicalCovered, candidate.start(), candidate.end()) ? RuleAction.ALLOW : RuleAction.MASK;
	}

	private static RuleAction classifyCalendarYear(String normalized, int yearEnd) {
		if (CALENDAR_INSULT_VOCATIVE.matcher(normalized).region(yearEnd, normalized.length()).lookingAt()) {
			return RuleAction.MASK;
		}
		Matcher hanja = LOCAL_CALENDAR_HANJA.matcher(normalized).region(yearEnd, normalized.length());
		if (!hanja.lookingAt()) return RuleAction.REVIEW;
		if (CALENDAR_INSULT_VOCATIVE.matcher(normalized).region(hanja.end(), normalized.length()).lookingAt()) {
			return RuleAction.MASK;
		}
		return RuleAction.ALLOW;
	}

	private static boolean hasContextualCandidate(String normalized, List<int[]> lexicalRanges) {
		if (PERSON_VOCATIVE_PATTERN.matcher(normalized).find()
			|| PERSON_DEMONSTRATIVE_PATTERN.matcher(normalized).find()) return true;
		for (int[] range : lexicalRanges) {
			if (LEXICAL_COMPARISON_PERSON_REFERENCE.matcher(normalized)
				.region(range[1], normalized.length()).lookingAt()) return true;
		}
		return false;
	}

	private static boolean isCovered(boolean[] allowed, int start, int end) {
		for (int i = start; i < end; i++) if (!allowed[i]) return false;
		return true;
	}

	private String mask(String original, String normalized, List<int[]> matches) {
		// 정규화로 길이가 달라져도 원문의 grapheme 경계를 이용해 탐지 부분만 가린다.
		BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
		iterator.setText(original);
		StringBuilder mappedText = new StringBuilder();
		List<Integer> starts = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		int start = iterator.first();
		for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
			String cluster = Normalizer.normalize(original.substring(start, end), Normalizer.Form.NFKC);
			mappedText.append(cluster);
			for (int i = 0; i < cluster.length(); i++) { starts.add(start); ends.add(end); }
			start = end;
		}
		// 경계를 개별 정규화한 결과가 다르면 원문을 노출하지 않는 전체 가림으로 처리한다.
		if (!mappedText.toString().equals(normalized)) return MASKED_MESSAGE;
		boolean[] masked = new boolean[original.length()];
		boolean[] normalizedMasked = new boolean[normalized.length()];
		for (int[] match : matches) {
			Arrays.fill(masked, starts.get(match[0]), ends.get(match[1] - 1), true);
			Arrays.fill(normalizedMasked, match[0], match[1], true);
		}
		// 탐지된 글자마다 별표 하나를 출력하고, 우회용 공백·기호 등은 개수에서 제외한다.
		// 정규화된 위치의 합집합으로 세므로 겹치는 매칭도 중복 마스킹하지 않는다.
		int[] starCounts = new int[original.length()];
		for (int i = 0; i < normalized.length();) {
			int cp = normalized.codePointAt(i);
			if (normalizedMasked[i]
				&& !SEPARATOR_PATTERN.matcher(new String(Character.toChars(cp))).matches()) {
				starCounts[starts.get(i)]++;
			}
			i += Character.charCount(cp);
		}
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < original.length(); i++) {
			if (masked[i]) {
				result.append("*".repeat(starCounts[i]));
			} else {
				result.append(original.charAt(i));
			}
		}
		return result.toString();
	}
}
