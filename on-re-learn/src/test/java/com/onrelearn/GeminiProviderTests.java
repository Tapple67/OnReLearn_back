package com.onrelearn;

import com.onrelearn.ai.*;
import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInput;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GeminiProviderTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUIZ = """
        {"title":"quiz","items":[{"type":"tf","question":"원문 문장 판단","explanation":"E",
        "answerKey":{"correct":true},"evidence":{"source":"pdf","page":1,"quote":"원문에 존재하는 검증 가능한 PDF 문장입니다."}}]}
        """;

    private static String response(String text) {
        var root = JSON.createObjectNode();
        root.putArray("candidates").addObject().put("finishReason", "STOP")
                .putObject("content").put("role", "model").putArray("parts")
                .addObject().put("text", text);
        return JSON.writeValueAsString(root);
    }

    private static class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<String> requests = new ArrayList<>(), paths = new ArrayList<>(), keys = new ArrayList<>();
        final Queue<String> responses = new ArrayDeque<>();
        final Queue<Integer> statuses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        int status = 200;
        Fixture(String... outputs) throws Exception {
            responses.addAll(List.of(outputs));
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().toString());
                keys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String output = responses.poll();
                if (output!=null && exchange.getRequestURI().getPath().contains("summary-test")) output="data: "+output+"\n\n";
                byte[] body = (output == null ? "private-upstream-error" : output).getBytes(StandardCharsets.UTF_8);
                Integer nextStatus = statuses.poll();
                exchange.sendResponseHeaders(nextStatus == null ? status : nextStatus, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/models/{model}:generateContent"; }
        MockEnvironment settings() {
            return new MockEnvironment().withProperty("onrelearn.ai.gemini.api-key", "synthetic-gemini")
                    .withProperty("onrelearn.ai.summary.provider", "gemini")
                    .withProperty("onrelearn.ai.summary.model", "summary-test")
                    .withProperty("onrelearn.ai.summary.endpoint", endpoint())
                    .withProperty("onrelearn.ai.quiz.provider", "gemini")
                    .withProperty("onrelearn.ai.quiz.model", "quiz-test")
                    .withProperty("onrelearn.ai.quiz.endpoint", endpoint());
        }
        GeminiClient client(int retries) { return new GeminiClient(endpoint(), "synthetic", "test", Duration.ofSeconds(2), retries, Duration.ZERO); }
        public void close() { server.stop(0); }
    }

    private SummaryInput summaryInput() {
        return new SummaryInput("material", "subject", 1, "title", SummaryPrompt.VERSION,
                List.of(new SummaryInput.Page(1, "원문에 존재하는 검증 가능한 PDF 문장입니다.", "note", null, null)));
    }
    private QuizContext quizContext() { return new QuizContext("summary", List.of(), List.of(new QuizContext.SourcePage(1,"원문에 존재하는 검증 가능한 PDF 문장입니다.","",null))); }

    @Test void groundingRetryKeepsValidQuestionsFromBothResponses() throws Exception {
        var first = (tools.jackson.databind.node.ObjectNode) JSON.readTree(QUIZ);
        var items = (tools.jackson.databind.node.ArrayNode) first.path("items");
        var invalid = (tools.jackson.databind.node.ObjectNode) items.get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) invalid.path("evidence")).put("page", 99);
        items.add(invalid);
        var second = first.deepCopy();
        var retryItems = (tools.jackson.databind.node.ArrayNode) second.path("items");
        retryItems.remove(1);
        var valid = (tools.jackson.databind.node.ObjectNode) retryItems.get(0);
        valid.put("question", "검증 가능한 문장 판단"); ((tools.jackson.databind.node.ObjectNode) valid.path("evidence")).put("page", 2);
        var context = new QuizContext("summary", List.of(), List.of(
                quizContext().sourcePages().get(0),
                new QuizContext.SourcePage(2, "원문에 존재하는 검증 가능한 PDF 문장입니다.", "", null)));
        try (var fixture = new Fixture(response(first.toString()), response(second.toString()))) {
            var stages = new ArrayList<String>();
            var result = new AiProviderConfiguration().quizProvider(fixture.settings())
                    .generate(context, new QuizSpec(0, 2, 0), null, stages::add);
            assertTrue(stages.contains("grounding_retry"));
            assertEquals(2, fixture.requests.size());
        }
    }

    @Test void summaryAndQuizSelectIndependentModelsAndPreserveSchemasAndMetadata() throws Exception {
        try (var f = new Fixture(response("{\"page\":1,\"quote\":\"원문에 존재하는 검증 가능한 PDF 문장입니다.\"}\n"), response(QUIZ))) {
            var config = new AiProviderConfiguration();
            var summary = config.summaryProvider(f.settings()).generate(summaryInput());
            var quiz = config.quizProvider(f.settings()).generate(quizContext(), new QuizSpec(0, 1, 0), null);
            assertEquals("gemini", summary.provider()); assertEquals("summary-test", summary.model());
            assertTrue(summary.content().contains("원문에 존재하는 검증 가능한 PDF 문장입니다.")); assertEquals(SummaryPrompt.VERSION, summary.promptVersion());
            assertEquals("gemini", quiz.provider()); assertEquals("quiz-test", quiz.model());
            assertEquals(List.of("/models/summary-test:streamGenerateContent?alt=sse", "/models/quiz-test:generateContent"), f.paths);
            assertEquals(List.of("synthetic-gemini", "synthetic-gemini"), f.keys);
            var summaryRequest = JSON.readTree(f.requests.get(0));
            assertTrue(summaryRequest.path("contents").get(0).path("parts").get(0).path("text").asString().contains("PDF"));
            assertFalse(summaryRequest.has("generationConfig")); assertFalse(summaryRequest.has("reasoning"));
            var quizRequest = JSON.readTree(f.requests.get(1));
            assertEquals("application/json", quizRequest.path("generationConfig").path("responseMimeType").asString());
            assertEquals("object", quizRequest.path("generationConfig").path("responseJsonSchema").path("type").asString());
            assertFalse(quizRequest.has("text"));
        }
    }

    @Test void openAiGenerationCanUseGeminiCriticWithIndependentKeyModelAndRepair() throws Exception {
        var openai = JSON.createObjectNode().put("status", "completed");
        openai.putArray("output").addObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .putArray("content").addObject().put("type", "output_text").put("text", QUIZ);
        try (var f = new Fixture(JSON.writeValueAsString(openai), response("{\"items\":[{\"index\":0,\"score\":1}]}"), JSON.writeValueAsString(openai))) {
            var env = f.settings().withProperty("onrelearn.ai.quiz.provider", "openai")
                    .withProperty("onrelearn.ai.quiz.api-key", "synthetic-openai")
                    .withProperty("onrelearn.ai.quiz.endpoint", f.endpoint().replace("{model}", "generator"))
                    .withProperty("onrelearn.ai.quiz-critic.enabled", "true")
                    .withProperty("onrelearn.ai.quiz-critic.provider", "gemini")
                    .withProperty("onrelearn.ai.quiz-critic.model", "critic-test")
                    .withProperty("onrelearn.ai.quiz-critic.endpoint", f.endpoint());
            var result = new AiProviderConfiguration().quizProvider(env).generate(quizContext(), new QuizSpec(0, 1, 0), null);
            assertEquals("openai", result.provider()); assertEquals(3, f.requests.size());
            assertEquals("/models/critic-test:generateContent", f.paths.get(1));
            assertEquals("synthetic-gemini", f.keys.get(1));
            assertEquals("object", JSON.readTree(f.requests.get(1)).path("generationConfig").path("responseJsonSchema").path("type").asString());
        }
    }

    @Test void invalidJsonIsRepairedAndInvalidQuizNeverBecomesSuccess() throws Exception {
        try (var f = new Fixture(response("invalid"), response(QUIZ))) {
            var result = new AiProviderConfiguration().quizProvider(f.settings()).generate(quizContext(), new QuizSpec(0, 1, 0), null);
            assertEquals("gemini", result.provider()); assertEquals(2, f.requests.size());
        }
        try (var f = new Fixture(response("{}"), response("{}"))) {
            var error = assertThrows(ResponseStatusException.class, () -> new AiProviderConfiguration().quizProvider(f.settings())
                    .generate(quizContext(), new QuizSpec(0, 1, 0), null));
            assertEquals(502, error.getStatusCode().value());
        }
    }

    @Test void retriesSchemaRejectionInJsonModeAndSeparatesHttpFromQuizParsing() throws Exception {
        try (var f = new Fixture("upstream rejection", response(QUIZ))) {
            f.statuses.add(400);
            new AiProviderConfiguration().quizProvider(f.settings()).generate(quizContext(), new QuizSpec(0, 1, 0), null);
            assertEquals(2, f.requests.size());
            var config = JSON.readTree(f.requests.get(1)).path("generationConfig");
            assertEquals("application/json", config.path("responseMimeType").asString());
            assertFalse(config.has("responseJsonSchema"));
        }
        try (var f = new Fixture()) {
            f.status = 403;
            var error = assertThrows(AiResponseFailure.class, () -> new AiProviderConfiguration().quizProvider(f.settings())
                    .generate(quizContext(), new QuizSpec(0, 1, 0), null));
            assertEquals(AiResponseFailure.Code.PROVIDER_HTTP, error.code());
            assertEquals(403, error.upstreamStatus());
        }
        try (var f = new Fixture(response("not json"), response("still not json"))) {
            var error = assertThrows(AiResponseFailure.class, () -> new AiProviderConfiguration().quizProvider(f.settings())
                    .generate(quizContext(), new QuizSpec(0, 1, 0), null));
            assertEquals(AiResponseFailure.Code.QUIZ_JSON, error.code());
        }
    }

    @Test void incompleteBlockedMalformedAndEmptyOutputAreRejectedWithoutPayloadLeaks() {
        for (String raw : List.of("private-invalid-json", "{}", response(""),
                response("partial").replace("STOP", "MAX_TOKENS"), response("blocked").replace("STOP", "SAFETY"),
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")) {
            var error = assertThrows(ResponseStatusException.class, () -> GeminiResponse.text(raw));
            assertEquals(502, error.getStatusCode().value()); assertNull(error.getCause());
            assertFalse(error.getMessage().contains("private-invalid"));
        }
        assertEquals("answer", GeminiResponse.text("""
            {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[
            {"thought":true,"text":"private thought"},{"text":"answer"}]}}]}
            """));
    }

    @Test void missingGeminiKeyDoesNotFallbackToOpenAiAndUnknownProviderFailsSafely() throws Exception {
        try (var f = new Fixture()) {
            var env = f.settings().withProperty("onrelearn.ai.gemini.api-key", "")
                    .withProperty("onrelearn.ai.openai.api-key", "synthetic-openai");
            var error = assertThrows(ResponseStatusException.class, () -> new AiProviderConfiguration().summaryProvider(env).generate(summaryInput()));
            assertEquals(503, error.getStatusCode().value()); assertTrue(f.requests.isEmpty());
            var unknown = assertThrows(IllegalArgumentException.class, () -> new AiProviderConfiguration()
                    .summaryProvider(env.withProperty("onrelearn.ai.summary.provider", "unsupported")));
            assertTrue(unknown.getMessage().contains("provider must be"));
        }
    }

    @Test void transientErrorsRetryButPermanentErrorsAndRedirectsDoNot() throws Exception {
        try (var f = new Fixture()) {
            f.status = 503;
            var error = assertThrows(ResponseStatusException.class, () -> f.client(1).request("prompt", null));
            assertEquals(502, error.getStatusCode().value()); assertEquals(2, f.requests.size());
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("private-upstream"));
            for (int status : List.of(400, 401, 302)) {
                f.status = status;
                assertThrows(ResponseStatusException.class, () -> f.client(3).request("prompt", null));
            }
            assertEquals(5, f.requests.size());
        }
    }

    @Test void springRegistersSingleProviderPerPurposeWithoutLoadingUserFiles() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment());
            context.register(AiProviderConfiguration.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(com.onrelearn.summary.ai.SummaryProvider.class).size());
            assertEquals(1, context.getBeansOfType(QuizProvider.class).size());
            assertInstanceOf(com.onrelearn.summary.ai.SummaryProvider.class,
                    context.getBean(com.onrelearn.summary.ai.SummaryProvider.class));
        }
    }

    @Test void responseBodyTimeoutIsBoundedAndDoesNotRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
            try { Thread.sleep(300); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client = new GeminiClient("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "test",
                    Duration.ofMillis(150), 3, Duration.ZERO);
            var error = assertThrows(ResponseStatusException.class, () -> client.request("prompt", null));
            assertEquals(504, error.getStatusCode().value()); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
