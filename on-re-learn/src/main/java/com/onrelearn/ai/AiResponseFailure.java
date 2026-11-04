package com.onrelearn.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// ! Only fixed diagnostic codes and numeric HTTP status are exposed.
public class AiResponseFailure extends ResponseStatusException {
    public enum Code { PROVIDER_HTTP, PROVIDER_TRANSPORT, PROVIDER_RESPONSE, QUIZ_JSON, QUIZ_STRUCTURE, QUIZ_GROUNDING }

    private final Code code;
    private final Integer upstreamStatus;

    public AiResponseFailure(Code code, Integer upstreamStatus) {
        super(HttpStatus.BAD_GATEWAY, "AI generation failed");
        this.code = code;
        this.upstreamStatus = upstreamStatus;
    }

    public Code code() { return code; }
    public Integer upstreamStatus() { return upstreamStatus; }
}
