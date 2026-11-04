package com.onrelearn.summary.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

// * HTTP response parsing is separate from transport and domain result validation.
public final class OpenAiSummaryResponse {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private OpenAiSummaryResponse() {}

    public static SummaryResult parse(String body, String requestedModel, String version) {
        try {
            var root = JSON.readTree(body);
            if (!"completed".equals(root.path("status").asString()) || !root.path("output").isArray()) throw invalid();
            StringBuilder text = new StringBuilder();
            for (var item : root.path("output")) {
                if (!"message".equals(item.path("type").asString())) continue;
                if (!"assistant".equals(item.path("role").asString()) || !"completed".equals(item.path("status").asString())) throw invalid();
                for (var part : item.path("content")) {
                    if ("refusal".equals(part.path("type").asString())) throw invalid();
                    if ("output_text".equals(part.path("type").asString())) {
                        if (!part.path("text").isString()) throw invalid();
                        text.append(part.path("text").asString());
                    }
                }
            }
            return new SummaryResult(text.toString().strip(), null, null, "openai", requestedModel, version);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // ! Never retain provider payloads or parse errors in the public exception chain.
            throw invalid();
        }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid summary response");
    }
}
