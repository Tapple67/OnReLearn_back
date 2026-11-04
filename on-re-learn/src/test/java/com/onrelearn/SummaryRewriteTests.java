package com.onrelearn;

import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SummaryRewriteTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEFINITION = "암호학적 해시 함수는 임의 길이의 메시지를 입력받아 고정 길이의 메시지 다이제스트를 생성한다.";
    private static final String NAMES = "해시 함수의 결과는 해시 값, 해시 코드 또는 해시 체크섬이라고 부른다.";
    private static final String USES = "해시 함수는 파일 체크섬 확인과 메시지 위변조 검출에 사용된다.";
    private static final String TEXT = DEFINITION + "\n\n" + NAMES + "\n\n" + USES;
    private static final String CLAIM = "**임의 길이의 메시지**를 받아 **고정 길이 다이제스트**를 만든다.";

    private static Map<String, Object> claim(int page, String text, String quote) {
        return new HashMap<>(Map.of("page", page, "claim", text, "quote", quote));
    }
    private static String line(Map<String, Object> fields) { return JSON.writeValueAsString(fields) + "\n"; }
    private static String summarizeWith(String pdf, String memo, String... lines) {
        var pages = List.of(new SummaryInput.Page(1, pdf, memo, null, null));
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION, pages), text -> {});
        var links = new SummaryNoteLinks(pages, false);
        gate.linkNotes(links);
        for (String value : lines) gate.append(value);
        return gate.finish() + links.finish();
    }
    private static String summarize(String... lines) { return summarizeWith(TEXT, "", lines); }
    private static boolean accepted(String claimText, String quote) {
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Page(1, TEXT, "", null, null))), text -> {});
        gate.append(line(claim(1, claimText, quote)));
        return gate.hasVerifiedExcerpts();
    }

    @Test void rewrittenSentenceIsPublishedAsNumberedPointWithItsPage() {
        String output = summarize(line(claim(1, CLAIM, DEFINITION)));
        assertTrue(output.contains("1. " + CLAIM + " (p.1)"), output);
        assertFalse(output.contains(DEFINITION), output);
    }
    @Test void quoteMissingFromThePageIsRejected() {
        assertFalse(accepted(CLAIM, "원문에 전혀 없는 인용 문장입니다."));
    }
    @Test void sentenceUnrelatedToItsEvidenceIsRejected() {
        assertFalse(accepted("양자 컴퓨터는 광자를 이용해 병렬 연산을 수행한다.", DEFINITION));
    }
    @Test void numberAbsentFromTheSourceIsRejected() {
        assertFalse(accepted("해시 함수는 임의 길이의 메시지를 256 길이의 다이제스트로 만든다.", DEFINITION));
        assertTrue(accepted("해시 함수는 임의 길이의 메시지를 고정 길이 다이제스트로 만든다.", DEFINITION));
    }
    @Test void numberPresentInTheSourceIsAccepted() {
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Page(1, "SHA-256 해시 함수의 출력은 256비트 길이의 값이다.", "", null, null))), text -> {});
        gate.append(line(claim(1, "SHA-256은 256비트 길이의 값을 출력한다.", "SHA-256 해시 함수의 출력은 256비트 길이의 값이다.")));
        assertTrue(gate.hasVerifiedExcerpts());
    }
    private static boolean acceptedOn(String pdf, String claimText, String quote) {
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Page(1, pdf, "", null, null))), text -> {});
        gate.append(line(claim(1, claimText, quote)));
        return gate.hasVerifiedExcerpts();
    }
    @Test void droppingANegationFromTheEvidenceIsRejected() {
        String pdf = "지구가 평평하다는 주장은 거짓이다.";
        assertFalse(acceptedOn(pdf, "지구는 평평한 형태이다.", "지구가 평평하다는 주장"));
        assertTrue(acceptedOn(pdf, "지구가 평평하다는 주장은 거짓이다.", "지구가 평평하다는 주장"));
    }
    @Test void addingANegationTheEvidenceDoesNotHaveIsRejected() {
        assertFalse(acceptedOn(TEXT, "해시 함수는 임의 길이의 메시지를 입력받지 않는다.", DEFINITION));
    }
    @Test void aNegationElsewhereOnThePageDoesNotBlockAPositiveSentence() {
        String pdf = "역상 저항성은 해시 값만으로 입력을 찾기 어렵다는 성질이다.\n\n해시 함수는 입력을 복원할 수 없다.";
        assertTrue(acceptedOn(pdf, "**역상 저항성**은 해시 값만으로 입력을 찾기 어렵다는 성질이다.", "역상 저항성은 해시 값만으로 입력을 찾기 어렵다는 성질이다."));
    }
    @Test void veryShortOrVeryLongSentencesAreRejected() {
        assertFalse(accepted("해시", DEFINITION));
        assertFalse(accepted("임의 길이의 메시지 ".repeat(40), DEFINITION));
    }
    @Test void subPointsAreLetteredAndAFailingOneLeavesNoGap() {
        var parent = claim(1, CLAIM, DEFINITION);
        parent.put("sub", List.of(
            claim(1, "결과는 해시 값 또는 해시 코드라고 부른다.", NAMES),
            claim(1, "양자 컴퓨터는 광자로 병렬 연산을 한다.", NAMES),
            claim(1, "파일 체크섬 확인에도 사용된다.", USES)));
        String output = summarize(line(parent));
        assertTrue(output.contains("  a. 결과는 해시 값 또는 해시 코드라고 부른다. (p.1)"), output);
        assertTrue(output.contains("  b. 파일 체크섬 확인에도 사용된다. (p.1)"), output);
        assertFalse(output.contains("양자"), output);
    }
    @Test void aRejectedParentDropsItsSubPoints() {
        var parent = claim(1, "양자 컴퓨터는 광자로 병렬 연산을 한다.", DEFINITION);
        parent.put("sub", List.of(claim(1, "결과는 해시 값 또는 해시 코드라고 부른다.", NAMES)));
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Page(1, TEXT, "", null, null))), text -> {});
        gate.append(line(parent));
        assertFalse(gate.hasVerifiedExcerpts());
    }
    @Test void numberingRestartsUnderEachTopicAndHeadingsAreNumbered() {
        var first = claim(1, CLAIM, DEFINITION); first.put("topic", "해시 함수의 정의");
        var second = claim(1, "결과는 해시 값 또는 해시 코드라고 부른다.", NAMES); second.put("topic", "해시 함수의 정의");
        var third = claim(1, "파일 체크섬 확인에 사용된다.", USES); third.put("topic", "해시 함수의 용도");
        String output = summarize(line(first), line(second), line(third));
        assertTrue(output.contains("### 1.1 해시 함수의 정의"), output);
        assertTrue(output.contains("2. 결과는"), output);
        assertTrue(output.contains("### 1.2 해시 함수의 용도"), output);
        assertTrue(output.contains("1. 파일 체크섬"), output);
    }
    @Test void writtenTopicNeedsToRelateToTheSource() {
        var fields = claim(1, CLAIM, DEFINITION); fields.put("topic", "양자 역학 입문");
        String output = summarize(line(fields));
        assertFalse(output.contains("양자"), output);
        assertTrue(output.contains("### 1.1 핵심 내용"), output);
    }
    @Test void onlyBoldMarkupSurvivesAndASentenceCannotInjectStructure() {
        for (String injected : List.of("## " + CLAIM, "> 쉽게 풀어보면 · " + CLAIM, "- " + CLAIM, "3. " + CLAIM)) {
            String output = summarize(line(claim(1, injected, DEFINITION)));
            assertTrue(output.contains("1. " + CLAIM + " (p.1)") || output.contains("1. 쉽게 풀어보면"), output);
            assertFalse(output.contains("\n## " + CLAIM) || output.contains("\n> 쉽게"), output);
        }
        String unbalanced = summarize(line(claim(1, "**임의 길이의 메시지를 받아 고정 길이 다이제스트를 만든다.", DEFINITION)));
        assertFalse(unbalanced.contains("**"), unbalanced);
    }
    @Test void pageMarkersWrittenInsideTheSentenceAreNotDuplicated() {
        String output = summarize(line(claim(1, CLAIM + " (p.1)", DEFINITION)));
        assertEquals(1, output.split("\\(p\\.1\\)", -1).length - 1, output);
    }
    @Test void repeatedSentenceIsPublishedOnce() {
        String output = summarize(line(claim(1, CLAIM, DEFINITION)), line(claim(1, CLAIM, DEFINITION)));
        assertEquals(1, output.split("다이제스트\\*\\*를 만든다", -1).length - 1, output);
    }
    @Test void difficultyMemoAttachesItsExplanationBelowTheSentence() {
        var fields = claim(1, CLAIM, DEFINITION); fields.put("easy", "길이가 달라도 같은 크기로 줄이는 도구예요.");
        String output = summarizeWith(TEXT, "해시 함수가 어려워", line(fields));
        assertTrue(output.indexOf("1. " + CLAIM) < output.indexOf("> 쉽게 풀어보면 · 길이가"), output);
        assertTrue(output.contains("> 내 메모 · 해시 함수가 어려워 (p.1)"), output);
    }
    @Test void explanationWithoutADifficultyMemoIsDiscarded() {
        var fields = claim(1, CLAIM, DEFINITION); fields.put("easy", "길이가 달라도 같은 크기로 줄이는 도구예요.");
        assertFalse(summarize(line(fields)).contains("길이가 달라도"));
    }
    @Test void aLineWithoutASentenceStillPublishesTheVerifiedExcerpt() {
        String output = summarize(JSON.writeValueAsString(Map.of("page", 1, "quote", DEFINITION)) + "\n");
        assertTrue(output.contains("- " + DEFINITION + " (p.1)"), output);
    }
    @Test void whenNoSentencePassesTheSourceFallbackStillPublishes() {
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Page(1, TEXT, "", null, null))), text -> {});
        gate.append(line(claim(1, "양자 컴퓨터는 광자로 병렬 연산을 한다.", DEFINITION)));
        assertFalse(gate.hasVerifiedExcerpts());
        gate.publishSourceFallback();
        assertTrue(gate.finish().contains("- " + DEFINITION + " (p.1)"));
    }
    @Test void aSectionSplitAcrossBatchesKeepsItsTitleAndContinuesSubsectionNumbers() {
        var page = new SummaryInput.Page(1, TEXT, "", null, null);
        var section = new SummaryOutline.Section(2, "해시 함수", List.of(page));
        var firstFields = claim(1, CLAIM, DEFINITION); firstFields.put("topic", "해시 함수의 정의");
        var first = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION, List.of(page)), text -> {}, section, 0);
        first.append(line(firstFields));
        var nextFields = claim(1, "파일 체크섬 확인에 사용된다.", USES); nextFields.put("topic", "해시 함수의 용도");
        var next = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION, List.of(page)), text -> {}, section, first.subsectionCount());
        next.append(line(nextFields));
        assertTrue(first.finish().contains("## 2. 해시 함수 · 1쪽"));
        String later = next.finish();
        assertFalse(later.lines().anyMatch(text -> text.startsWith("## ")), later);
        assertTrue(later.contains("### 2.2 해시 함수의 용도"), later);
        assertTrue(later.contains("1. 파일 체크섬"), later);
    }
    @Test void countsWhySentencesWereRejectedWithoutKeepingTheirText() {
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Page(1, TEXT, "", null, null))), text -> {});
        var parent = claim(1, CLAIM, DEFINITION);
        parent.put("sub", List.of(claim(1, "결과는 해시 값이라고 부른다.", NAMES), claim(1, "결과는 해시 값 256개라고 부른다.", NAMES)));
        gate.append(line(parent));
        gate.append(line(claim(1, CLAIM, DEFINITION)));
        gate.append(line(claim(1, "해시", DEFINITION)));
        gate.append(line(claim(1, "원문에 없는 인용을 근거로 쓴 문장이다.", "원문에 전혀 없는 인용 문장입니다.")));
        gate.append(line(claim(1, "양자 컴퓨터는 광자로 병렬 연산을 한다.", DEFINITION)));
        gate.append(line(claim(1, "해시 함수는 메시지를 입력받지 않는다.", DEFINITION)));
        var stats = gate.statistics();
        assertEquals(2, stats.get("accepted"));
        assertEquals(1, stats.get("duplicate"));
        assertEquals(1, stats.get("length"));
        assertEquals(1, stats.get("quote"));
        assertEquals(1, stats.get("terms"));
        assertEquals(1, stats.get("number"));
        assertEquals(1, stats.get("negation"));
        assertFalse(stats.toString().contains("해시"));
    }
    @Test void promptAsksForRewrittenSentencesWithEvidence() {
        var input = new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION, List.of(new SummaryInput.Page(1, TEXT, "", null, null)));
        String prompt = new SummaryPrompt().render(input);
        for (String field : List.of("\"claim\"", "\"quote\"", "\"sub\"", "\"easy\"")) assertTrue(prompt.contains(field), field);
        assertTrue(SummaryPrompt.VERSION.contains("rewrite"));
    }
}
