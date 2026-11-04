package com.onrelearn.ai;

import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

public final class GeminiResponse {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private GeminiResponse() {}

    public static String text(String body) {
        try {
            var root = JSON.readTree(body);
            if (root.path("promptFeedback").has("blockReason")) throw invalid();
            var candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.size() != 1) throw invalid();
            var candidate = candidates.get(0);
            if (!"STOP".equals(candidate.path("finishReason").asString())
                    || !"model".equals(candidate.path("content").path("role").asString())) throw invalid();
            StringBuilder text = new StringBuilder();
            for (var part : candidate.path("content").path("parts")) {
                // ! Thinking text is internal and must never become saved study content.
                if (part.path("thought").asBoolean(false)) continue;
                if (!part.path("text").isString()) throw invalid();
                text.append(part.path("text").asString());
            }
            if (text.toString().isBlank()) throw invalid();
            return text.toString().strip();
        } catch (ResponseStatusException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private static ResponseStatusException invalid() {
        // ! Do not attach the upstream payload or parser error to public exceptions.
        return new AiResponseFailure(AiResponseFailure.Code.PROVIDER_RESPONSE, 200);
    }
}
