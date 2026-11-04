package com.onrelearn.quiz.ai;

import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// * Internal review contract. The provider decides whether optional review failures are recoverable.
public record QuizCritique(JsonNode output, int itemCount) {
    public QuizCritique {
        require(itemCount > 0 && output != null && output.isObject());
        var items = output.get("items");
        require(items != null && items.isArray());
        for (var item : items) {
            require(item.isObject());
            index(item.get("index"), itemCount);
            var score = item.get("score");
            require(score != null && score.isIntegralNumber() && score.canConvertToInt()
                    && score.intValue() >= 1 && score.intValue() <= 5);
            var problems = item.get("problems");
            if (problems != null) {
                require(problems.isArray());
                for (var problem : problems) require(problem.isString());
            }
            var regenerate = item.get("regenerate");
            if (regenerate != null) require(regenerate.isBoolean());
        }
        var indexes = output.get("regenerateIndexes");
        if (indexes != null) {
            require(indexes.isArray());
            for (var entry : indexes) index(entry, itemCount);
        }
        output = output.deepCopy();
    }
    public boolean needsRepair() {
        // * Legacy selection uses item scores/flags, not regenerateIndexes alone.
        for (var item : output.get("items")) {
            if (item.get("score").intValue() <= 2
                    || (item.has("regenerate") && item.get("regenerate").booleanValue())) return true;
        }
        return false;
    }
    private static void index(JsonNode value, int count) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0 && value.intValue() < count);
    }
    private static void require(boolean value) {
        // ! Do not expose review text, original answers or notes through exceptions.
        if (!value) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid quiz critique");
    }
    @Override public JsonNode output() { return output.deepCopy(); }
    @Override public String toString() { return "QuizCritique[content omitted]"; }
}
