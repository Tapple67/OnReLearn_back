package com.onrelearn;

import com.onrelearn.ai.AiTextClient;
import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.quiz.service.QuizInputService;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizSourceScopeTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static SummaryInput document(int pages) {
        var list = new ArrayList<SummaryInput.Page>();
        for (int page = 1; page <= pages; page++) list.add(new SummaryInput.Page(page, "개념"+page+" 설명 문장입니다. 충분히 긴 원문 내용입니다.", "", null, null));
        return new SummaryInput("m","s",1,"t",SummaryPrompt.VERSION,list);
    }
    private static String summaryOf(int pages) {
        var lines = new StringBuilder("## 1. 전체 절\n");
        for (int page = 1; page <= pages; page++) lines.append(page).append(". 개념").append(page).append(" 요약 (p.").append(page).append(")\n");
        return lines.toString();
    }
    private static List<Integer> pagesOf(QuizContext context) { return context.sourcePages().stream().map(QuizContext.SourcePage::page).toList(); }

    @Test void longDocumentSourcesSpreadOverTheWholeDocument() {
        var pages = pagesOf(QuizInputService.groundedContext(summaryOf(60), document(60), List.of()));
        assertEquals(15,pages.size());
        assertTrue(pages.get(pages.size()-1)>=50,"last page "+pages);
        assertTrue(pages.stream().anyMatch(page -> page>20 && page<40),"middle "+pages);
    }

    @Test void summaryOnlyKeepsLinesThatTheModelCanCite() {
        var context = QuizInputService.groundedContext(summaryOf(60), document(60), List.of());
        var pages = pagesOf(context);
        assertTrue(context.summary().contains("## 1. 전체 절"));
        for (int page = 1; page <= 60; page++)
            assertEquals(pages.contains(page),context.summary().contains("(p."+page+")"),"page "+page);
    }

    @Test void shortDocumentKeepsEveryPageAndTheWholeSummary() {
        String summary = summaryOf(15);
        var context = QuizInputService.groundedContext(summary, document(15), List.of());
        assertEquals(IntStream.rangeClosed(1,15).boxed().toList(),pagesOf(context));
        assertEquals(summary,context.summary());
    }

    @Test void reviewQuizKeepsItsSummary() {
        String summary = summaryOf(60);
        assertEquals(summary,QuizInputService.groundedContext(summary, document(60), List.of(5d)).summary());
    }

    private static String tf(String question, int page, String quote) {
        return JSON.writeValueAsString(Map.of("type","tf","question",question,"choices",List.of("O","X"),"answerKey",Map.of("correct",true),
                "explanation","E","topic","T","points",1,"evidence",Map.of("source","pdf","page",page,"quote",quote),"signalHits",List.of()));
    }
    private static String quiz(String... items) { return "{\"title\":\"t\",\"items\":["+String.join(",",items)+"]}"; }

    @Test void repairAsksForSpareItemsAndKeepsOnlyWhatIsMissing() {
        String quote = "개념1 설명 문장입니다. 충분히 긴 원문 내용입니다.";
        var prompts = new ArrayList<String>();
        var outputs = new ArrayDeque<>(List.of(
                quiz(tf("개념1 첫 번째 판단",1,quote)),
                quiz(tf("개념1 어긋난 판단",1,"원문에 전혀 없는 인용 문장입니다 여기는"),tf("개념1 두 번째 판단",1,quote),tf("개념1 세 번째 판단",1,quote))));
        var client = new AiTextClient() {
            @Override public String request(String prompt, JsonNode format) { prompts.add(prompt); return outputs.poll(); }
            @Override public String provider() { return "scripted"; }
        };
        var context = QuizInputService.groundedContext("요약", document(1), List.of());
        var result = new AiQuizProvider(client,"m",client,"c",false).generate(context,new QuizSpec(0,2,0),null);
        assertEquals(2,prompts.size());
        assertTrue(prompts.get(1).contains("- tf 3"),"asks one missing item plus spares");
        assertEquals(2,result.output().path("items").size());
    }

    @Test void questionFormatAndUnrelatedQuestionGiveDifferentGuidance() {
        String quote = "개념1 설명 문장입니다. 충분히 긴 원문 내용입니다.";
        var prompts = new ArrayList<String>();
        var outputs = new ArrayDeque<>(List.of(
                quiz(tf("개념1 설명은 3쪽에 있는가",1,quote)),
                quiz(tf("개념1 판단",1,quote))));
        var client = new AiTextClient() {
            @Override public String request(String prompt, JsonNode format) { prompts.add(prompt); return outputs.poll(); }
            @Override public String provider() { return "scripted"; }
        };
        new AiQuizProvider(client,"m",client,"c",false).generate(QuizInputService.groundedContext("요약", document(1), List.of()),new QuizSpec(0,1,0),null);
        assertTrue(prompts.get(1).contains("페이지 표시나 빈칸"),prompts.get(1));
        assertFalse(prompts.get(1).contains("인용 문단과 무관"));
    }
}
