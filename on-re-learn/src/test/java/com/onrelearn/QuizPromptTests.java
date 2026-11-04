package com.onrelearn;

import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class QuizPromptTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final QuizSpec spec = new QuizSpec(0,1,0);
    private QuizContext context() {
        return new QuizContext("literal %s ${summaryMarkdown}",
                List.of(new QuizContext.Note(2,"private note",null)),
                List.of(new QuizContext.SourcePage(2,"PDF evidence","private note",null)));
    }
    @Test void rendersGeneralContextCountsAndLiteralUserText() {
        String prompt = new QuizPrompt().render(context(),spec,null);
        assertTrue(prompt.contains("- mcq 0"));
        assertTrue(prompt.contains("- tf 1"));
        assertTrue(prompt.contains("- short 0"));
        assertTrue(prompt.contains("literal %s ${summaryMarkdown}"));
        assertTrue(prompt.contains("private note"));
        assertTrue(prompt.contains("PDF evidence"));
        assertTrue(prompt.contains("최대 1개"));
        assertFalse(prompt.contains("60%"));
        assertTrue(prompt.contains("evidence.page"));
        assertEquals("quiz_v12_memo_requests",QuizPrompt.VERSION);
    }
    @Test void earlierGroundedConceptQuizzesAreNotFlaggedAsUnverified() {
        assertTrue(QuizPrompt.grounded(QuizPrompt.VERSION));
        assertTrue(QuizPrompt.grounded("quiz_v11_memo_aware"));
        assertTrue(QuizPrompt.grounded("quiz_v10_source_based"));
        assertTrue(QuizPrompt.grounded("quiz_v9_grounded_concepts"));
        assertFalse(QuizPrompt.grounded("quiz_v6"));
        assertFalse(QuizPrompt.grounded(null));
    }
    @Test void distinguishesReviewFocusAndCopiesEvidence() {
        var evidence = json.createObjectNode().put("page",2);
        var item = new QuizFocus.WrongItem("wrong question","topic",null,evidence);
        evidence.put("page",9);
        var focus = new QuizFocus(QuizFocus.Kind.WRONG_REVIEW,List.of("topic"),List.of(item));
        String wrong = new QuizPrompt().render(context(),spec,focus);
        assertTrue(wrong.contains("WRONG_REVIEW"));
        assertTrue(wrong.contains("wrongItems"));
        assertTrue(wrong.contains("wrong question"));
        assertEquals(2,item.evidence().get("page").intValue());
        String weak = new QuizPrompt().render(context(),spec,
                new QuizFocus(QuizFocus.Kind.WEAK_TOPIC,List.of("topic"),List.of()));
        assertTrue(weak.contains("WEAK_TOPIC"));
        assertFalse(weak.contains("wrongItems"));
        assertFalse(focus.toString().contains("topic"));
        assertThrows(IllegalArgumentException.class, () -> new QuizFocus(
                QuizFocus.Kind.WEAK_TOPIC,List.of("topic"),List.of(item)));
    }
    @Test void providerResultEnforcesOutputAndMetadataBeforeUse() {
        var output = json.readTree("{\"items\":[{\"type\":\"tf\",\"question\":\"Q\",\"explanation\":\"E\",\"answerKey\":{\"correct\":true}}]}");
        QuizProvider provider = (context,spec,focus) -> new QuizResult(output,spec,"synthetic","model",QuizPrompt.VERSION);
        var result = provider.generate(context(),spec,null);
        ((ObjectNode)output.get("items").get(0)).put("question","changed");
        assertEquals("Q",result.output().get("items").get(0).get("question").stringValue());
        ((ObjectNode)result.output().get("items").get(0)).put("question","changed again");
        assertEquals("Q",result.output().get("items").get(0).get("question").stringValue());
        assertFalse(result.toString().contains("answerKey"));
        for (String model : new String[]{null," "}) {
            var error = assertThrows(ResponseStatusException.class,
                () -> new QuizResult(output,spec,"synthetic",model,QuizPrompt.VERSION));
            assertEquals(502,error.getStatusCode().value());
            assertEquals("Invalid quiz response",error.getReason());
        }
        assertThrows(ResponseStatusException.class, () -> new QuizResult(
                output,new QuizSpec(1,0,0),"synthetic","model",QuizPrompt.VERSION));
    }
}
