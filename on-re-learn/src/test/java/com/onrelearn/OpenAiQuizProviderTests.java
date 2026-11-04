package com.onrelearn;
import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiQuizProviderTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static String quiz(String title, String source, int count) {
        String item = "{\"type\":\"tf\",\"question\":\"Q\",\"explanation\":\"E\",\"answerKey\":{\"correct\":true},\"evidence\":{\"source\":\""+source+"\",\"page\":1,\"quote\":\"실제 PDF에 포함된 근거 문장입니다.\"}}";
        return "{\"title\":\""+title+"\",\"items\":["+String.join(",",Collections.nCopies(count,item))+"]}";
    }
    private QuizContext context(boolean notes) {
        return new QuizContext("summary",notes ? List.of(new QuizContext.Note(1,"note",null)) : List.of(),List.of(new QuizContext.SourcePage(1,"실제 PDF에 포함된 근거 문장입니다.","",null)));
    }
    private static class Fixture implements AutoCloseable {
        final HttpServer server;
        final Queue<String> responses = new ArrayDeque<>();
        final List<String> requests = new ArrayList<>();
        Fixture(String... outputs) throws Exception {
            responses.addAll(List.of(outputs));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String output = responses.poll();
                if (output == null || output.equals("HTTP_ERROR")) {
                    exchange.sendResponseHeaders(503,-1); exchange.close(); return;
                }
                var root = JSON.createObjectNode().put("status","completed");
                var message = root.putArray("output").addObject().put("type","message").put("role","assistant").put("status","completed");
                message.putArray("content").addObject().put("type","output_text").put("text",output);
                byte[] body = JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }
        QuizProvider provider(boolean review) {
            var env = new StandardEnvironment();
            Map<String,Object> settings = new HashMap<>();
            for (String purpose : List.of("quiz","quiz-critic")) {
                String prefix = "onrelearn.ai."+purpose;
                settings.put(prefix+".endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/"+purpose);
                settings.put(prefix+".api-key","synthetic");
                settings.put(prefix+".model",purpose+"-model");
                settings.put(prefix+".timeout","PT3S");
                settings.put(prefix+".reasoning-effort","");
            }
            settings.put("onrelearn.ai.quiz-critic.enabled",review);
            env.getPropertySources().addFirst(new MapPropertySource("synthetic",settings));
            return new com.onrelearn.ai.AiProviderConfiguration().quizProvider(env);
        }
        public void close() { server.stop(0); }
    }
    @Test void generatesWithoutCallingDisabledCriticAndBindsSettings() throws Exception {
        try (var f = new Fixture(quiz("original","pdf",1))) {
            var result = f.provider(false).generate(context(false),new QuizSpec(0,1,0),null);
            assertEquals("원문 근거 퀴즈",result.output().get("title").stringValue());
            assertEquals("quiz-model",result.model());
            assertEquals(1,f.requests.size());
            var request = JSON.readTree(f.requests.get(0));
            assertEquals("quiz-model",request.get("model").stringValue());
            assertFalse(request.has("reasoning"));
            assertEquals("quiz_result",request.path("text").path("format").path("name").asString());
        }
    }
    @Test void repairsJsonAndCountsWithoutUsingNotesAsFacts() throws Exception {
        try (var f = new Fixture("invalid",quiz("wrong count","pdf",2),quiz("low coverage","pdf",1))) {
            var result = f.provider(false).generate(context(true),new QuizSpec(0,1,0),null);
            assertEquals("원문 근거 퀴즈",result.output().get("title").stringValue());
            assertEquals(3,f.requests.size());
            assertTrue(JSON.readTree(f.requests.get(1)).path("input").asString().contains("valid JSON"));
            assertTrue(JSON.readTree(f.requests.get(2)).path("input").asString().contains("exactly mcq"));
        }
    }
    @Test void criticCanRepairUsingSeparateModelAndSchema() throws Exception {
        try (var f = new Fixture(quiz("original","pdf",1),
                "{\"items\":[{\"index\":0,\"score\":1}]}",quiz("repaired","pdf",1))) {
            var result = f.provider(true).generate(context(false),new QuizSpec(0,1,0),null);
            assertEquals("원문 근거 퀴즈",result.output().get("title").stringValue());
            assertEquals(3,f.requests.size());
            var review = JSON.readTree(f.requests.get(1));
            assertEquals("quiz-critic-model",review.get("model").stringValue());
            assertEquals("quiz_critique",review.path("text").path("format").path("name").asString());
            assertEquals("quiz-model",JSON.readTree(f.requests.get(2)).get("model").stringValue());
        }
    }
    @Test void preservesOriginalOnReviewOrRepairFailure() throws Exception {
        for (String[] outputs : List.of(
                new String[]{quiz("original","pdf",1),"HTTP_ERROR"},
                new String[]{quiz("original","pdf",1),"{\"items\":[{\"index\":0,\"score\":1}]}","invalid","invalid"})) {
            try (var f = new Fixture(outputs)) {
                var result = f.provider(true).generate(context(false),new QuizSpec(0,1,0),null);
                assertEquals("원문 근거 퀴즈",result.output().get("title").stringValue());
            }
        }
    }
    @Test void failsInvalidFinalCountsAndDoesNotRepairTransportErrors() throws Exception {
        try (var f = new Fixture(quiz("wrong","pdf",2),quiz("still wrong","pdf",2))) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> f.provider(false).generate(context(false),new QuizSpec(0,1,0),null));
            assertEquals(502,error.getStatusCode().value());
            assertEquals(2,f.requests.size());
        }
        try (var f = new Fixture("HTTP_ERROR")) {
            assertThrows(ResponseStatusException.class,
                    () -> f.provider(false).generate(context(false),new QuizSpec(0,1,0),null));
            assertEquals(1,f.requests.size());
        }
    }
}
