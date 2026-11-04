package com.onrelearn;

import com.onrelearn.ai.*;
import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizSourceBasedTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MAC = "메시지 인증코드(MAC)는 공유 비밀키로 메시지 무결성과 송신자 인증을 제공한다.";
    private static final String SIGN = "디지털 서명은 개인키로 서명하고 공개키로 검증하여 부인 방지를 제공한다.";
    private static final String HASH = "해시 함수는 임의 길이의 입력을 고정 길이의 출력으로 변환하는 함수이다.";
    private static final String PAGE = MAC + "\n\n" + SIGN + "\n\n" + HASH;
    private static final String OTHER = "키 교환 프로토콜은 안전하지 않은 채널에서 세션키를 합의하는 절차이다.";
    private static final QuizContext CONTEXT = new QuizContext("요약", List.of(), List.of(
            new QuizContext.SourcePage(1, PAGE, "", null), new QuizContext.SourcePage(2, OTHER, "", null)));

    private static final class Scripted implements AiTextClient {
        final Deque<String> outputs = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        Scripted(String... values) { outputs.addAll(List.of(values)); }
        @Override public String request(String prompt, JsonNode format) {
            prompts.add(prompt);
            return outputs.size() > 1 ? outputs.poll() : outputs.peek();
        }
        @Override public String provider() { return "scripted"; }
    }

    private static String tf(String question, int page, String quote) {
        return JSON.writeValueAsString(Map.of("type", "tf", "question", question, "choices", List.of("O", "X"),
                "answerKey", Map.of("correct", true), "explanation", "모델 해설", "topic", "주제", "points", 1,
                "evidence", Map.of("source", "pdf", "page", page, "quote", quote), "signalHits", List.of()));
    }
    private static String mcq(String quote, String... choices) {
        return JSON.writeValueAsString(Map.of("type", "mcq", "question", "메시지 무결성을 확인하려면 어떤 방식이 맞는가?",
                "choices", List.of(choices), "answerKey", Map.of("correctIndex", 0), "explanation", "모델 해설", "topic", "주제",
                "points", 1, "evidence", Map.of("source", "pdf", "page", 1, "quote", quote), "signalHits", List.of()));
    }
    private static String shortItem(String... accepted) {
        return JSON.writeValueAsString(Map.of("type", "short", "question", "공유 비밀키로 무결성을 제공하는 방식은?",
                "answerKey", Map.of("accepted", List.of(accepted)), "explanation", "모델 해설", "topic", "주제", "points", 1,
                "evidence", Map.of("source", "pdf", "page", 1, "quote", MAC), "signalHits", List.of()));
    }
    private static JsonNode quiz(String... items) {
        return JSON.readTree("{\"title\":\"t\",\"items\":[" + String.join(",", items) + "]}");
    }
    private static String[] macQuestions(int count) {
        var items = new String[count];
        for (int index = 0; index < count; index++) items[index] = tf("메시지 인증코드 판단 " + index, 1, MAC);
        return items;
    }
    private static JsonNode first(JsonNode output) { return output.path("items").get(0); }
    private static void assertGroundingFailure(JsonNode output, QuizSpec spec) {
        var failure = assertThrows(AiResponseFailure.class, () -> GroundedQuiz.verify(output, CONTEXT, spec));
        assertEquals(AiResponseFailure.Code.QUIZ_GROUNDING, failure.code());
    }

    @Test void answerMayDifferInSpacingFromSource() {
        var output = quiz(mcq(MAC, "메시지 인증 코드", "디지털 서명", "해시 함수", "세션키"));
        assertEquals("메시지 인증 코드", first(GroundedQuiz.verify(output, CONTEXT, new QuizSpec(1, 0, 0))).path("choices").get(0).asString());
    }
    @Test void wrongChoicesMayBeRephrasedWhenTheyUseSourceConcepts() {
        var output = quiz(mcq(MAC, "메시지 인증코드", "공개키로 서명한다", "입력을 고정 길이로 변환한다", "세션키를 합의한다"));
        assertEquals(4, first(GroundedQuiz.verify(output, CONTEXT, new QuizSpec(1, 0, 0))).path("choices").size());
    }
    @Test void choiceWithoutAnySourceConceptIsRejected() {
        assertGroundingFailure(quiz(mcq(MAC, "메시지 인증코드", "디지털 서명", "해시 함수", "블랙홀 사건의 지평선")), new QuizSpec(1, 0, 0));
    }
    @Test void choicesDifferingOnlyInSpacingAreDuplicates() {
        assertGroundingFailure(quiz(mcq(MAC, "메시지 인증코드", "메시지 인증 코드", "해시 함수", "세션키")), new QuizSpec(1, 0, 0));
    }
    @Test void answerMaySitInAnotherParagraphOfTheCitedPage() {
        var output = quiz(mcq(MAC, "디지털 서명", "메시지 인증코드", "해시 함수", "세션키"));
        assertEquals(1, GroundedQuiz.verify(output, CONTEXT, new QuizSpec(1, 0, 0)).path("items").size());
    }
    @Test void answerOnlyFoundOnAnotherPageIsRejected() {
        assertGroundingFailure(quiz(mcq(MAC, "세션키 합의", "메시지 인증코드", "디지털 서명", "해시 함수")), new QuizSpec(1, 0, 0));
    }
    @Test void shortAnswerKeepsOnlyAliasesSupportedBySource() {
        var output = quiz(shortItem("MAC", "메시지 인증 코드", "우주 탐사선"));
        var accepted = first(GroundedQuiz.verify(output, CONTEXT, new QuizSpec(0, 0, 1))).path("answerKey").path("accepted");
        assertEquals(List.of("MAC", "메시지 인증 코드"), accepted.valueStream().map(JsonNode::asString).toList());
    }
    @Test void shortAnswerWithoutSupportedAliasIsRejected() {
        assertGroundingFailure(quiz(shortItem("우주 탐사선")), new QuizSpec(0, 0, 1));
    }
    @Test void paraphrasedTrueFalseClaimIsKeptAndShownWithOriginalParagraph() {
        var item = first(GroundedQuiz.verify(quiz(tf("MAC을 쓰려면 공개키가 꼭 필요하다", 1, MAC)), CONTEXT, new QuizSpec(0, 1, 0)));
        assertEquals("MAC을 쓰려면 공개키가 꼭 필요하다", item.path("question").asString());
        assertEquals("원문 근거 (p.1):\n" + MAC, item.path("explanation").asString());
    }
    @Test void questionUnrelatedToQuotedParagraphIsRejected() {
        assertGroundingFailure(quiz(tf("블랙홀은 빛도 탈출할 수 없다", 1, MAC)), new QuizSpec(0, 1, 0));
    }
    @Test void quoteMayDifferInSpacingAndPunctuation() {
        var quote = "메시지 인증 코드 (MAC) 는 공유 비밀키로 메시지 무결성과 송신자 인증을 제공한다";
        assertEquals(MAC, first(GroundedQuiz.verify(quiz(tf("메시지 인증코드 판단", 1, quote)), CONTEXT, new QuizSpec(0, 1, 0))).path("evidence").path("quote").asString());
    }
    @Test void quoteMayDifferInEndingsWhenAlmostAllTermsMatchOneParagraph() {
        var quote = "메시지 인증코드는 공유 비밀키를 사용해서 메시지 무결성과 송신자 인증을 제공합니다";
        assertEquals(MAC, first(GroundedQuiz.verify(quiz(tf("메시지 인증코드 판단", 1, quote)), CONTEXT, new QuizSpec(0, 1, 0))).path("evidence").path("quote").asString());
    }
    @Test void quoteAcrossParagraphBoundaryKeepsBothParagraphs() {
        var item = first(GroundedQuiz.verify(quiz(tf("메시지 인증코드 판단", 1, "송신자 인증을 제공한다. 디지털 서명은")), CONTEXT, new QuizSpec(0, 1, 0)));
        assertEquals(MAC + " " + SIGN, item.path("evidence").path("quote").asString());
    }
    @Test void wrongCitedPageIsCorrectedToThePageHoldingTheQuote() {
        for (int cited : new int[]{1, 99}) {
            var evidence = first(GroundedQuiz.verify(quiz(tf("세션키 합의 판단", cited, OTHER)), CONTEXT, new QuizSpec(0, 1, 0))).path("evidence");
            assertEquals(2, evidence.path("page").intValue());
        }
    }
    @Test void inventedQuoteIsRejectedEvenWithRealPage() {
        assertGroundingFailure(quiz(tf("메시지 인증코드 판단", 1, "블랙홀은 빛도 탈출할 수 없는 시공간 영역이다")), new QuizSpec(0, 1, 0));
    }
    @Test void quoteSharingOnlyAFewTermsIsNotAccepted() {
        assertGroundingFailure(quiz(tf("메시지 인증코드 판단", 1, "메시지 인증코드는 양자 컴퓨터의 큐비트 오류를 정정하는 표면 부호이다")), new QuizSpec(0, 1, 0));
    }

    private static QuizResult generate(Scripted client, QuizSpec spec) {
        return new AiQuizProvider(client, "model", client, "critic", false).generate(CONTEXT, spec, null);
    }
    private static String text(JsonNode output) { return JSON.writeValueAsString(output); }

    @Test void surplusItemsAreDroppedWithoutAnotherRequest() {
        var client = new Scripted(text(quiz(macQuestions(11))));
        assertEquals(10, generate(client, new QuizSpec(0, 10, 0)).output().path("items").size());
        assertEquals(1, client.prompts.size());
    }
    @Test void missingItemsAreRequestedOnceWithoutRepeatingValidOnes() {
        var client = new Scripted(text(quiz(macQuestions(9))), text(quiz(tf("디지털 서명 판단", 1, SIGN))));
        assertEquals(10, generate(client, new QuizSpec(0, 10, 0)).output().path("items").size());
        assertEquals(2, client.prompts.size());
        assertTrue(client.prompts.get(1).contains("- tf 1"));
    }
    @Test void repairMayReturnMoreThanRequested() {
        var client = new Scripted(text(quiz(tf("메시지 인증코드 판단", 1, "없는 인용문이 여기에 들어갑니다"), tf("해시 함수 판단", 1, HASH))),
                text(quiz(tf("디지털 서명 판단", 1, SIGN), tf("세션키 판단", 2, OTHER))));
        assertEquals(2, generate(client, new QuizSpec(0, 2, 0)).output().path("items").size());
        assertEquals(2, client.prompts.size());
    }
    @Test void repairRequestExplainsWhyItemsWereRejected() {
        var client = new Scripted(text(quiz(tf("메시지 인증코드 판단", 1, "없는 인용문이 여기에 들어갑니다"))), text(quiz(tf("디지털 서명 판단", 1, SIGN))));
        generate(client, new QuizSpec(0, 1, 0));
        assertFalse(client.prompts.get(0).contains("[이전 탈락 사유]"));
        assertTrue(client.prompts.get(1).contains("[이전 탈락 사유]"));
        assertTrue(client.prompts.get(1).contains("tf 1건"));
    }
    @Test void secondRepairCanFillWhatTheFirstMissed() {
        var bad = text(quiz(tf("메시지 인증코드 판단", 1, "없는 인용문이 여기에 들어갑니다")));
        var client = new Scripted(bad, bad, text(quiz(tf("디지털 서명 판단", 1, SIGN))));
        assertEquals(1, generate(client, new QuizSpec(0, 1, 0)).output().path("items").size());
        assertEquals(3, client.prompts.size());
    }
    @Test void stopsAfterTwoRepairsAndSavesNothing() {
        var client = new Scripted(text(quiz(tf("메시지 인증코드 판단", 1, "없는 인용문이 여기에 들어갑니다"))));
        var failure = assertThrows(AiResponseFailure.class, () -> generate(client, new QuizSpec(0, 1, 0)));
        assertEquals(AiResponseFailure.Code.QUIZ_GROUNDING, failure.code());
        assertEquals(3, client.prompts.size());
    }
}
