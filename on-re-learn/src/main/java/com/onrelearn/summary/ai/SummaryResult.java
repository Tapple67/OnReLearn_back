package com.onrelearn.summary.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record SummaryResult(String content, String canonical, String adaptive,
                            String provider, String model, String promptVersion) {
    public SummaryResult {
        // ! Reject invalid upstream output without including its body in errors or logs.
        if (blank(content) || blank(provider) || blank(model) || blank(promptVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid summary response");
        }
        canonical = canonical == null ? content : canonical;
        adaptive = adaptive == null ? content : adaptive;
        if (blank(canonical) || blank(adaptive)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid summary response");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    // ! Generated text can include private notes.
    @Override public String toString() { return "SummaryResult[content omitted]"; }
}
