package com.onrelearn;

import com.onrelearn.ai.AiResponseFailure;
import com.onrelearn.quiz.ai.GroundedQuiz;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.quiz.service.QuizInputService;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

// * PDF pages usually have single line breaks only, so a whole page reads as one paragraph.
class QuizMemoLimitTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PAGES = 5;

    private static String memoSentence(int page) { return "대칭키암호 "+page+"번 방식은 같은 열쇠로 암호화와 복호화를 수행합니다."; }
    private static String otherSentence(int page) { return "해시함수 "+page+"번 방식은 입력을 고정된 길이의 값으로 변환합니다."; }

    private static SummaryInput memoOnEveryPage() {
        var pages = new ArrayList<SummaryInput.Page>();
        for (int page = 1; page <= PAGES; page++)
            pages.add(new SummaryInput.Page(page, memoSentence(page)+"\n"+otherSentence(page), "대칭키암호 "+page+"번 방식이 헷갈려", null, null));
        return new SummaryInput("m","s",1,"t",SummaryPrompt.VERSION,pages);
    }

    private static Map<String,Object> item(int page, String question, String quote) {
        return Map.of("type","tf","question",question,"choices",List.of("O","X"),"answerKey",Map.of("correct",true),
                "explanation","E","topic","T","points",1,"evidence",Map.of("source","pdf","page",page,"quote",quote),"signalHits",List.of());
    }

    private static JsonNode verify(List<Map<String,Object>> items) {
        var context = QuizInputService.groundedContext("요약", memoOnEveryPage(), List.of());
        return GroundedQuiz.verify(JSON.valueToTree(Map.of("title","t","items",items)), context, new QuizSpec(0,items.size(),0));
    }

    @Test void questionsOffTheMemoConceptAreNotCountedOnAMemoPage() {
        var items = new ArrayList<Map<String,Object>>();
        for (int page = 1; page <= PAGES; page++) items.add(item(page,"해시함수 "+page+"번 방식은 고정 길이 값을 만드는가",otherSentence(page)));
        var verified = verify(items).path("items");
        assertEquals(PAGES,verified.size());
        for (var question : verified) assertTrue(question.path("signalHits").isEmpty());
    }

    @Test void questionsOnTheMemoConceptStayCapped() {
        var items = new ArrayList<Map<String,Object>>();
        for (int page = 1; page <= PAGES; page++) items.add(item(page,"대칭키암호 "+page+"번 방식은 같은 열쇠를 쓰는가",memoSentence(page)));
        assertThrows(AiResponseFailure.class,() -> verify(items));
    }

    @Test void aMemoQuestionQuotingOtherWordsStillCounts() {
        var items = new ArrayList<Map<String,Object>>();
        for (int page = 1; page <= PAGES; page++) items.add(item(page,"대칭키암호 "+page+"번 방식과 비교한 설명으로 맞는가",otherSentence(page)));
        assertThrows(AiResponseFailure.class,() -> verify(items));
    }

    @Test void oneMemoQuestionRecordsItsConcept() {
        var items = new ArrayList<Map<String,Object>>();
        items.add(item(1,"대칭키암호 1번 방식은 같은 열쇠를 쓰는가",memoSentence(1)));
        for (int page = 2; page <= PAGES; page++) items.add(item(page,"해시함수 "+page+"번 방식은 고정 길이 값을 만드는가",otherSentence(page)));
        var verified = verify(items).path("items");
        assertEquals(PAGES,verified.size());
        assertFalse(verified.get(0).path("signalHits").isEmpty());
    }
}
