package com.onrelearn.ai;

import tools.jackson.databind.JsonNode;

// * Provider transport boundary; prompts, repair and domain validation remain with each purpose.
public interface AiTextClient {
    String request(String prompt, JsonNode format);
    String provider();
}
