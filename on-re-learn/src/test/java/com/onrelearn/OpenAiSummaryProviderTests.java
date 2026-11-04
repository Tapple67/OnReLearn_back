package com.onrelearn;

import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.service.SummaryInput;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiSummaryProviderTests {
    private static final String OK = "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Summary\"}]}]}";
    private SummaryInput input() { return SummaryInput.assemble("m", "s", 1, "Title", SummaryPrompt.VERSION,
            List.of(new SummaryInput.Text(1, "PDF")), List.of()); }
    private OpenAiSummaryProvider provider(HttpServer server, String key, String reasoning, int retries, Duration timeout) {
        return new OpenAiSummaryProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/responses", key,
                "synthetic-model", reasoning, timeout, retries, Duration.ZERO);
    }

    @Test void sendsConfiguredRequestAndParsesRawResponsesOutput() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/responses", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = OK.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var result = provider(server, "synthetic-key", "low", 0, Duration.ofSeconds(3)).generate(input());
            var body = JsonMapper.builder().build().readTree(request.get());
            assertEquals("synthetic-model", body.path("model").asString());
            assertEquals("low", body.path("reasoning").path("effort").asString());
            assertFalse(body.path("store").asBoolean());
            assertTrue(body.path("input").asString().contains("PDF"));
            assertEquals("Bearer synthetic-key", authorization.get());
            assertEquals("Summary", result.canonical());
            assertEquals(result.canonical(), result.adaptive());
            provider(server, "synthetic-key", "", 0, Duration.ofSeconds(3)).generate(input());
            assertFalse(JsonMapper.builder().build().readTree(request.get()).has("reasoning"));
        } finally { server.stop(0); }
    }

    @Test void retriesOnlyExplicitTransientHttpErrorsAndKeepsErrorsSafe() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger(); AtomicInteger mode = new AtomicInteger(503);
        server.createContext("/responses", exchange -> {
            int call = calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            int status = mode.get() == 503 && call > 1 ? 200 : mode.get();
            byte[] bytes = (status == 200 ? OK : "private-provider-error").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            assertEquals("Summary", provider(server, "synthetic", "", 1, Duration.ofSeconds(3)).generate(input()).content());
            assertEquals(2, calls.get());
            for (int code : new int[] {400, 401, 302, 429}) {
                calls.set(0); mode.set(code);
                var error = assertThrows(ResponseStatusException.class, () -> provider(server, "synthetic", "", code == 429 ? 1 : 3, Duration.ofSeconds(3)).generate(input()));
                assertEquals(502, error.getStatusCode().value());
                assertFalse(error.toString().contains("private-provider-error"));
                assertEquals(code == 429 ? 2 : 1, calls.get());
            }
            calls.set(0);
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> provider(server, "", "", 0, Duration.ofSeconds(3)).generate(input())).getStatusCode().value());
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsMalformedEmptyIncompleteAndRefusedResponses() {
        for (String body : new String[] {"not-json", "{}", "{\"status\":\"completed\",\"output\":[]}",
                OK.replace("completed", "incomplete"), OK.replace("output_text", "refusal"), OK.replace("Summary", " ")}) {
            var error = assertThrows(ResponseStatusException.class, () -> OpenAiSummaryResponse.parse(body, "test", SummaryPrompt.VERSION));
            assertEquals(502, error.getStatusCode().value());
            assertNull(error.getCause());
        }
    }

    @Test void timesOutOnDelayedResponseBodyWithoutRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(500); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var error = assertThrows(ResponseStatusException.class, () -> provider(server, "synthetic", "", 2, Duration.ofMillis(200)).generate(input()));
            assertEquals(504, error.getStatusCode().value());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
