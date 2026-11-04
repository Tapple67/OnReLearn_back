package com.onrelearn.quiz.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

public final class QuizAnswerInput {
    private QuizAnswerInput() {}
    public static void validate(String type,JsonNode choices,JsonNode response) {
        if(response==null || !response.isObject()) throw invalid();
        String field=switch(type) {
            case "mcq" -> "selectedIndex";
            case "tf" -> "value";
            case "short" -> "text";
            default -> throw new IllegalStateException("Unsupported stored quiz type");
        };
        var value=response.get(field);
        if(value==null) throw invalid();
        // * Null selections and empty text are the original editor's unanswered states.
        boolean valid=switch(type) {
            case "mcq" -> value.isNull() || (value.isIntegralNumber() && value.canConvertToInt()
                    && choices!=null && choices.isArray() && value.intValue()>=0 && value.intValue()<choices.size());
            case "tf" -> value.isNull() || value.isBoolean();
            default -> value.isString();
        };
        if(!valid) throw invalid();
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quiz response");
    }
}
