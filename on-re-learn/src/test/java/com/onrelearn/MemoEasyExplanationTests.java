package com.onrelearn;

import com.onrelearn.ai.AiTextClient;
import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class MemoEasyExplanationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYMMETRIC = "대칭키 암호는 암호화와 복호화에 같은 키를 사용한다.";
    private static final String HASH = "해시 함수는 임의 길이의 입력을 고정 길이의 출력으로 변환한다.";
    private static final String EASY = "같은 열쇠로 문을 잠그고 여는 것과 같아요.";

    private static String line(int page, String quote, String easy) {
        var fields = new HashMap<String, Object>(Map.of("page", page, "quote", quote));
        if (easy != null) fields.put("easy", easy);
        return JSON.writeValueAsString(fields) + "\n";
    }
    private static String summarize(List<SummaryInput.Page> pages, String... lines) {
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION, pages), text -> {});
        var links = new SummaryNoteLinks(pages, false);
        gate.linkNotes(links);
        for (String value : lines) gate.append(value);
        return gate.finish() + links.finish();
    }
    private static String withMemo(String memo, String easy) {
        return summarize(List.of(new SummaryInput.Page(1, SYMMETRIC + "\n\n" + HASH, memo, null, null)), line(1, SYMMETRIC, easy));
    }

    @Test void difficultyMemoShowsTheNewExplanationBelowTheVerifiedExcerpt() {
        String output = withMemo("대칭키 암호가 어려워", EASY);
        assertTrue(output.indexOf("- " + SYMMETRIC) < output.indexOf("> 쉽게 풀어보면 · " + EASY), output);
        assertTrue(output.contains("> 내 메모 · 대칭키 암호가 어려워 (p.1)"), output);
        assertFalse(output.contains("추가 설명"), output);
    }
    @Test void easyRequestMemoShowsTheNewExplanation() {
        assertTrue(withMemo("대칭키 암호 쉽게 설명해줘", EASY).contains("> 쉽게 풀어보면 · " + EASY));
    }
    @Test void difficultyPhrasesWithoutAConceptApplyToTheirOwnPage() {
        for (String memo : List.of("이 페이지 어려워", "어려운 부분", "이해가 안 돼", "무슨 말인지", "여기 복잡해", "모르겠어")) {
            assertTrue(withMemo(memo, EASY).contains("> 쉽게 풀어보면 · " + EASY), memo);
        }
    }
    @Test void withoutMemoTheSummaryStaysSourceOnly() {
        assertFalse(withMemo("", EASY).contains(EASY));
    }
    @Test void memoWithoutDifficultyDoesNotAddAnExplanation() {
        String output = withMemo("대칭키 암호 비교해줘", EASY);
        assertFalse(output.contains(EASY), output);
        assertTrue(output.contains("추가 설명"), output);
    }
    @Test void difficultyAboutAnotherConceptDoesNotAddAnExplanationHere() {
        String output = withMemo("블랙홀이 어려워", EASY);
        assertFalse(output.contains(EASY), output);
        assertTrue(output.contains("> 내 메모 · 블랙홀이 어려워"), output);
    }
    @Test void pageWideDifficultyDoesNotReachOtherPages() {
        var pages = List.of(new SummaryInput.Page(1, SYMMETRIC, "이 페이지 어려워", null, null), new SummaryInput.Page(2, HASH, "", null, null));
        String output = summarize(pages, line(2, HASH, EASY));
        assertFalse(output.contains(EASY), output);
        assertTrue(output.contains("다시 볼 질문"), output);
    }
    @Test void oneMemoProducesOneExplanation() {
        var pages = List.of(new SummaryInput.Page(1, SYMMETRIC + "\n\n" + HASH, "이 페이지 어려워", null, null));
        String output = summarize(pages, line(1, SYMMETRIC, EASY), line(1, HASH, "두 번째 설명"));
        assertTrue(output.contains(EASY), output);
        assertFalse(output.contains("두 번째 설명"), output);
    }
    @Test void explanationCannotPublishAnUnverifiedQuote() {
        var pages = List.of(new SummaryInput.Page(1, SYMMETRIC, "이 페이지 어려워", null, null));
        var gate = new GroundedSummary(new SummaryInput("m", "s", 1, "t", SummaryPrompt.VERSION, pages), text -> {});
        gate.linkNotes(new SummaryNoteLinks(pages, false));
        gate.append(line(1, "원문에 전혀 없는 문장을 인용합니다", EASY));
        assertFalse(gate.hasVerifiedExcerpts());
    }
    @Test void explanationStaysOnOneLineAndWithinTheLimit() {
        String output = withMemo("대칭키 암호가 어려워", "첫 줄\n\n## 가짜 제목");
        assertTrue(output.contains("> 쉽게 풀어보면 · 첫 줄 ## 가짜 제목"), output);
        assertFalse(withMemo("대칭키 암호가 어려워", "긴 설명 ".repeat(200)).contains("긴 설명"));
    }

    @Test void optionalCriticCanJudgeMemoAlignment() {
        var prompts = new ArrayList<String>();
        var client = new AiTextClient() {
            @Override public String request(String prompt, JsonNode format) {
                prompts.add(prompt);
                return prompts.size() == 2 ? "{\"items\":[{\"index\":0,\"score\":5,\"problems\":[],\"regenerate\":false}],\"regenerateIndexes\":[]}"
                        : "{\"title\":\"t\",\"items\":[{\"type\":\"tf\",\"question\":\"대칭키 판단\",\"choices\":[\"O\",\"X\"],\"answerKey\":{\"correct\":true},\"explanation\":\"E\",\"topic\":\"T\",\"points\":1,\"evidence\":{\"source\":\"pdf\",\"page\":1,\"quote\":" + JSON.writeValueAsString(SYMMETRIC) + "},\"signalHits\":[]}]}";
            }
            @Override public String provider() { return "scripted"; }
        };
        var context = new QuizContext("요약", List.of(), List.of(new QuizContext.SourcePage(1, SYMMETRIC, "대칭키 비교 문제 내줘", null)));
        new AiQuizProvider(client, "m", client, "c", true).generate(context, new QuizSpec(0, 1, 0), null);
        assertTrue(prompts.get(1).contains("대칭키 비교 문제 내줘"));
        assertTrue(prompts.get(1).contains("noteAlignment: 사용자 메모 또는"));
    }
}
