package com.onrelearn.ai;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

public class GeminiClient implements AiTextClient {
    @Override public String provider() { return "gemini"; }
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String endpoint, key, model;
    private final Duration timeout, retryDelay;
    private final int retries;

    public GeminiClient(
            String endpoint,
            String key,
            String model,
            Duration timeout,
            int retries,
            Duration retryDelay) {
        this.endpoint = endpoint; this.key = key; this.model = model;
        this.timeout = timeout; this.retries = retries; this.retryDelay = retryDelay;
    }

    public String request(String prompt, JsonNode format) {
        URI uri = configuredEndpoint();
        var body = json.createObjectNode();
        body.putArray("contents").addObject().put("role", "user")
                .putArray("parts").addObject().put("text", prompt);
        if (format != null) {
            var config = body.putObject("generationConfig");
            config.put("responseMimeType", "application/json");
            config.set("responseJsonSchema", format.get("schema"));
        }
        var response = request(uri, body);
        // * Some models reject the structured schema. Keep JSON mode and validate locally.
        if (response.statusCode() == 400 && format != null) {
            body.putObject("generationConfig").put("responseMimeType", "application/json");
            response = request(uri, body);
        }
        if (response.statusCode() != 200) {
            throw new AiResponseFailure(AiResponseFailure.Code.PROVIDER_HTTP, response.statusCode());
        }
        return GeminiResponse.text(response.body());
    }

    private HttpResponse<String> request(URI uri, JsonNode body) {
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("x-goog-api-key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        for (int attempt = 0; ; attempt++) {
            var response = send(request);
            int status = response.statusCode();
            // * Retry explicit transient HTTP failures only; ambiguous timeout/I/O failures may already incur cost.
            boolean transientFailure = status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            if (!transientFailure || attempt >= retries) {
                return response;
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
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI provider timed out");
        } catch (InterruptedException exception) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI provider timed out");
            }
            throw new AiResponseFailure(AiResponseFailure.Code.PROVIDER_TRANSPORT, null);
        }
    }

    private URI configuredEndpoint() {
        if (key == null || key.isBlank() || key.contains("\r") || key.contains("\n") || model == null || model.isBlank()
                || timeout == null || timeout.toMillis() < 1 || retries < 0 || retries > 3
                || retryDelay == null || retryDelay.isNegative()) throw unavailable();
        try {
            if (!model.matches("[A-Za-z0-9._-]+")) throw unavailable();
            URI uri = URI.create(endpoint.replace("{model}", model));
            boolean local = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) throw unavailable();
            return uri;
        } catch (IllegalArgumentException exception) { throw unavailable(); }
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable");
    }
}
