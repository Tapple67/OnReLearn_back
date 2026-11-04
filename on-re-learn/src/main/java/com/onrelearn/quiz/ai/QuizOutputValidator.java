package com.onrelearn.quiz.ai;

import com.onrelearn.quiz.model.QuizSpec;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class QuizOutputValidator {
    private QuizOutputValidator() {}

    public static void validateStructure(JsonNode output) { validate(output, null); }

    public static void validate(JsonNode output, QuizSpec spec) {
        require(output != null && output.isObject());
        optionalText(output.get("title"));
        var items = output.get("items");
        require(items != null && items.isArray() && !items.isEmpty() && (spec == null || items.size() == spec.totalCount()));
        int mcq = 0, tf = 0, shortCount = 0;
        for (var item : items) {
            require(item.isObject());
            text(item.get("question"));
            text(item.get("explanation"));
            optionalText(item.get("topic"));
            var type = item.get("type");
            text(type);
            var answer = item.get("answerKey");
            require(answer != null && answer.isObject());
            var choices = item.get("choices");
            if (choices != null && !choices.isNull()) strings(choices, false);
            switch (type.stringValue()) {
                case "mcq" -> {
                    mcq++;
                    require(choices != null && choices.isArray() && choices.size() >= 2);
                    var index = answer.get("correctIndex");
                    require(index != null && index.isIntegralNumber() && index.canConvertToInt()
                        && index.intValue() >= 0 && index.intValue() < choices.size());
                }
                case "tf" -> {
                    tf++;
                    require(answer.get("correct") != null && answer.get("correct").isBoolean());
                }
                case "short" -> {
                    shortCount++;
                    strings(answer.get("accepted"), true);
                }
                default -> throw invalid();
            }
            var points = item.get("points");
            if (points != null) require(points.isIntegralNumber() && points.canConvertToInt() && points.intValue() > 0);
            var evidence = item.get("evidence");
            if (evidence != null) {
                require(evidence.isObject());
                text(evidence.get("source"));
                require(java.util.Set.of("pdf", "note", "mixed").contains(evidence.get("source").stringValue()));
                var page = evidence.get("page");
                if (page != null) require(page.isIntegralNumber() && page.canConvertToInt() && page.intValue() > 0);
                optionalText(evidence.get("quote"));
            }
            var hits = item.get("signalHits");
            if (hits != null) strings(hits, false);
        }
        if (spec != null) require(mcq == spec.mcqCount() && tf == spec.tfCount() && shortCount == spec.shortCount());
    }

    private static void strings(JsonNode value, boolean nonempty) {
        require(value != null && value.isArray() && (!nonempty || !value.isEmpty()));
        for (var entry : value) {
            require(entry.isString());
            if (nonempty) require(!entry.stringValue().isBlank());
        }
    }
    private static void optionalText(JsonNode value) {
        if (value != null && !value.isNull()) require(value.isString());
    }
    private static void text(JsonNode value) {
        require(value != null && value.isString() && !value.stringValue().isBlank());
    }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static ResponseStatusException invalid() {
        // ! Never expose provider output, answer keys or private notes in an error.
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid quiz response");
    }
}
