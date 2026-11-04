package com.onrelearn;

import com.onrelearn.quiz.ai.QuizOutputValidator;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.quiz.service.QuizPoints;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizOutputTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private String item(String type, String answer, String choices) {
        return "{\"type\":\"" + type + "\",\"question\":\"Question\",\"explanation\":\"Explanation\",\"answerKey\":"
            + answer + ",\"choices\":" + choices + "}";
    }
    private void validate(String item, QuizSpec spec) {
        QuizOutputValidator.validate(json.readTree("{\"items\":[" + item + "]}"), spec);
    }
    @Test void acceptsThreeTypesAndOptionalMetadata() {
        validate(item("mcq", "{\"correctIndex\":1}", "[\"A\",\"B\"]"), new QuizSpec(1,0,0));
        validate(item("tf", "{\"correct\":false}", "null"), new QuizSpec(0,1,0));
        validate(item("short", "{\"accepted\":[\"answer\"]}", "null"), new QuizSpec(0,0,1));
        var output = json.readTree("{\"title\":\"Quiz\",\"items\":[" +
            item("tf", "{\"correct\":true}", "null").replace("}", ",\"unused\":0}") + "]}");
        assertDoesNotThrow(() -> QuizOutputValidator.validate(output, new QuizSpec(0,1,0)));
    }
    @Test void rejectsInvalidAnswerTypesRangesAndCountsWithSafeErrors() {
        for (String answer : List.of("{\"correctIndex\":-1}", "{\"correctIndex\":2}", "{\"correctIndex\":0.5}", "{\"correctIndex\":\"0\"}")) {
            assertInvalid(item("mcq", answer, "[\"A\",\"B\"]"), new QuizSpec(1,0,0));
        }
        assertInvalid(item("mcq", "{\"correctIndex\":0}", "[\"A\"]"), new QuizSpec(1,0,0));
        assertInvalid(item("tf", "{\"correct\":\"true\"}", "null"), new QuizSpec(0,1,0));
        for (String answer : List.of("{\"accepted\":[]}", "{\"accepted\":[\" \"]}", "{\"accepted\":[42]}")) {
            assertInvalid(item("short", answer, "null"), new QuizSpec(0,0,1));
        }
        assertInvalid(item("tf", "{\"correct\":true}", "null"), new QuizSpec(1,0,0));
        assertInvalid(item("tf", "{\"correct\":true}", "null"), new QuizSpec(0,2,0));
        assertInvalid(item("tf", "{\"correct\":true}", "null").replace("Question", " "), new QuizSpec(0,1,0));
    }
    private void assertInvalid(String item, QuizSpec spec) {
        var error = assertThrows(ResponseStatusException.class, () -> validate(item, spec));
        assertEquals(502, error.getStatusCode().value());
        assertEquals("Invalid quiz response", error.getReason());
    }
    @Test void distributesLegacyPointsAndPreservesBoundaryBehavior() {
        assertEquals(List.of(), QuizPoints.normalize(List.of()));
        assertEquals(List.of(34,33,33), QuizPoints.normalize(List.of(1d,1d,1d)));
        assertEquals(List.of(26,74), QuizPoints.normalize(List.of(1d,3d)));
        assertEquals(List.of(34,33,33), QuizPoints.normalize(Arrays.asList(null,-1d,Double.NaN)));
        assertEquals(List.of(50,50), QuizPoints.normalize(List.of(Double.MAX_VALUE,Double.MAX_VALUE)));
        assertEquals(101, QuizPoints.normalize(Collections.nCopies(101,1d)).stream().mapToInt(Integer::intValue).sum());
        for (int size = 1; size < 100; size++) {
            var points = QuizPoints.normalize(Collections.nCopies(size, 1d));
            assertEquals(100, points.stream().mapToInt(Integer::intValue).sum());
            assertTrue(points.stream().allMatch(value -> value >= 1));
        }
    }
}
