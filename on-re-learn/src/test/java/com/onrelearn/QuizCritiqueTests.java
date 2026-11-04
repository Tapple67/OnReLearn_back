package com.onrelearn;
import com.onrelearn.quiz.ai.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class QuizCritiqueTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private QuizCritique review(int score, boolean regenerate) {
        return new QuizCritique(json.readTree("{\"items\":[{\"index\":0,\"score\":"+score+
                ",\"regenerate\":"+regenerate+"}],\"regenerateIndexes\":[0]}"),1);
    }
    @Test void usesLegacyScoresAndFlagsAndKeepsValidatedOutputIsolated() {
        assertTrue(review(2,false).needsRepair());
        assertTrue(review(5,true).needsRepair());
        assertFalse(review(3,false).needsRepair());
        assertFalse(new QuizCritique(json.readTree("{\"items\":[]}"),1).needsRepair());
        var critique = review(3,false);
        ((ObjectNode)critique.output().get("items").get(0)).put("score",1);
        assertFalse(critique.needsRepair());
        assertFalse(critique.toString().contains("regenerate"));
    }
    @Test void rejectsMalformedOrOutOfRangeReviewWithoutLeakingContent() {
        for (String item : List.of(
                "{\"index\":1,\"score\":3}", "{\"index\":-1,\"score\":3}",
                "{\"index\":0,\"score\":6}", "{\"index\":0,\"score\":0}",
                "{\"index\":0,\"score\":2.5}", "{\"index\":0,\"score\":3,\"regenerate\":\"yes\"}",
                "{\"index\":0,\"score\":3,\"problems\":[42]}")) {
            var error = assertThrows(ResponseStatusException.class,
                () -> new QuizCritique(json.readTree("{\"items\":["+item+"]}"),1));
            assertEquals(502,error.getStatusCode().value());
            assertEquals("Invalid quiz critique",error.getReason());
        }
        assertThrows(ResponseStatusException.class, () -> new QuizCritique(
                json.readTree("{\"items\":[],\"regenerateIndexes\":[1]}"),1));
    }
    @Test void rendersNoteCriterionAndRepairWithoutReinterpretingUserText() {
        var prompt = new QuizPrompt();
        var quiz = json.readTree("{\"title\":\"private %s\",\"items\":[]}");
        String withNotes = prompt.critique(quiz,List.of(new QuizContext.Note(1,"private note",null)));
        String withoutNotes = prompt.critique(quiz,List.of());
        assertNotEquals(withNotes,withoutNotes);
        assertTrue(withNotes.contains("private note"));
        assertTrue(withNotes.contains("private %s"));
        assertTrue(withoutNotes.contains("noteAlignment"));
        String repair = prompt.repair("base %s",quiz,review(1,false));
        assertTrue(repair.startsWith("base %s"));
        assertTrue(repair.contains("private %s"));
        assertTrue(repair.contains("regenerateIndexes"));
    }
}
