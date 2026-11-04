package com.onrelearn.quiz.ai;
import com.onrelearn.quiz.model.QuizSpec;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// * Internal provider output; learner-facing responses must omit answers until grading.
public record QuizResult(JsonNode output, QuizSpec spec, String provider, String model, String promptVersion) {
    public QuizResult {
        if (spec == null || blank(provider) || blank(model) || blank(promptVersion))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid quiz response");
        QuizOutputValidator.validate(output, spec);
        output = output.deepCopy();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    @Override public JsonNode output() { return output.deepCopy(); }
    @Override public String toString() { return "QuizResult[content omitted]"; }
}
