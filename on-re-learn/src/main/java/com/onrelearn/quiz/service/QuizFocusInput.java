package com.onrelearn.quiz.service;

import com.onrelearn.quiz.ai.QuizFocus;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// * Normalize review topics separately from database lookup and AI invocation.
public final class QuizFocusInput {
    private QuizFocusInput() {}

    public static List<String> topics(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        var topics = new LinkedHashSet<String>();
        for (var item : value) {
            if (!item.isString()) continue;
            // * Match JavaScript trim; preserve case and internal whitespace.
            String topic = item.stringValue().replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
            if (!topic.isEmpty()) topics.add(topic);
            if (topics.size() == 8) break;
        }
        return List.copyOf(topics);
    }

    public static QuizFocus weakTopic(JsonNode value) {
        var topics = topics(value);
        if (topics.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review topics are required");
        return new QuizFocus(QuizFocus.Kind.WEAK_TOPIC, topics, List.of());
    }
}
