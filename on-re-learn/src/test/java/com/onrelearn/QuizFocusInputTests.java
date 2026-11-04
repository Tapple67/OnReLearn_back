package com.onrelearn;

import com.onrelearn.quiz.service.QuizFocusInput;
import com.onrelearn.quiz.ai.QuizFocus;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizFocusInputTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void normalizesTopicsWithoutChangingOrderCaseOrInput() {
        var input = json.readTree("[\" A \",\"A\",null,1,false,{},\"\",\" \",\"a\",\"B  C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\"]");
        var before = input.deepCopy();
        assertEquals(List.of("A", "a", "B  C", "D", "E", "F", "G", "H"), QuizFocusInput.topics(input));
        assertEquals(before, input);
        assertThrows(UnsupportedOperationException.class, () -> QuizFocusInput.topics(input).add("x"));
        var unicode = json.createArrayNode().add("\u00a0\uFEFF Topic \u3000");
        assertEquals(List.of("Topic"), QuizFocusInput.topics(unicode));
    }

    @Test void weakTopicRequiresNonemptyNormalizedTopics() {
        for (var input : new tools.jackson.databind.JsonNode[]{null, json.readTree("null"), json.readTree("{}"),
                json.readTree("\"Topic\""), json.readTree("[]"), json.readTree("[null,42,\" \"]")}) {
            assertTrue(QuizFocusInput.topics(input).isEmpty());
            var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> QuizFocusInput.weakTopic(input));
            assertEquals(400, error.getStatusCode().value());
        }
        var focus = QuizFocusInput.weakTopic(json.readTree("[\" A \",\"A\",\"B\"]"));
        assertEquals(QuizFocus.Kind.WEAK_TOPIC, focus.kind());
        assertEquals(List.of("A", "B"), focus.topics());
        assertTrue(focus.wrongItems().isEmpty());
    }
}
