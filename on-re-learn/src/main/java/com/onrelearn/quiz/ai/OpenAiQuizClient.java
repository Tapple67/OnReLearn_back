package com.onrelearn.quiz.ai;

import tools.jackson.databind.JsonNode;
import com.onrelearn.ai.AiTextClient;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

public class OpenAiQuizClient implements AiTextClient {
    @Override public String provider() { return "openai"; }
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String endpoint, key, model, reasoning;
    private final Duration timeout, retryDelay;
    private final int retries;

    public OpenAiQuizClient(
            String endpoint,
            String key,
            String model,
            String reasoning,
            Duration timeout,
            int retries,
            Duration retryDelay) {
        this.endpoint = endpoint; this.key = key; this.model = model; this.reasoning = reasoning;
        this.timeout = timeout; this.retries = retries; this.retryDelay = retryDelay;
    }

    public String request(String prompt, JsonNode format) {
        URI uri = configuredEndpoint();
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("store", false);
        body.putObject("text").set("format", format);
        body.put("input", prompt);
        if (!reasoning.isBlank()) body.putObject("reasoning").put("effort", reasoning);
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        for (int attempt = 0; ; attempt++) {
            var response = send(request);
            int status = response.statusCode();
            if (status == 200) return OpenAiQuizResponse.text(response.body());
            // * Retry explicit transient HTTP failures only; ambiguous timeout/I/O failures may already incur cost.
            boolean transientFailure = status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            if (!transientFailure || attempt >= retries) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Quiz provider request failed");
            }
            try { Thread.sleep(retryDelay.toMillis()); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw unavailable(); }
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // ! Bound the whole response body wait, not only receipt of headers.
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Quiz provider timed out");
        } catch (InterruptedException exception) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Quiz provider timed out");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Quiz provider request failed");
        }
    }

    private URI configuredEndpoint() {
        if (key == null || key.isBlank() || key.contains("\r") || key.contains("\n") || model == null || model.isBlank()
                || reasoning == null || timeout == null || timeout.toMillis() < 1 || retries < 0 || retries > 3
                || retryDelay == null || retryDelay.isNegative()) throw unavailable();
        try {
            URI uri = URI.create(endpoint);
            boolean local = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) throw unavailable();
            return uri;
        } catch (IllegalArgumentException exception) { throw unavailable(); }
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quiz provider unavailable");
    }
}
