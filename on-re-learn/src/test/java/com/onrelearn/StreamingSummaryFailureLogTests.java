package com.onrelearn;
import com.onrelearn.ai.StreamingSummaryProvider;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInput;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

// * A failure inside a 200 stream must say which one it was, without the provider's message or the document.
@ExtendWith(OutputCaptureExtension.class)
class StreamingSummaryFailureLogTests {
    private static final String SECRET="private-upstream-message";

    private void fail(String provider, String event) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes=("data: "+event+"\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var ai=new StreamingSummaryProvider(provider,"http://127.0.0.1:"+server.getAddress().getPort()+"/models/{model}:generateContent","synthetic","test","",Duration.ofSeconds(3),8,16000,"low");
            var input=new SummaryInput("material","subject",1,"title",SummaryPrompt.VERSION,List.of(new SummaryInput.Page(1,"첫 원문 문장이고 정확히 발췌합니다. "+"이어지는 원문 문장입니다. ".repeat(10),"",null,null)));
            assertEquals(502,assertThrows(ResponseStatusException.class,()->ai.generate(input,text -> {})).getStatusCode().value());
        } finally {server.stop(0);}
    }

    @Test void geminiErrorEventNamesItsCodeOnly(CapturedOutput output) throws Exception {
        fail("gemini","{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\""+SECRET+"\"}}");
        assertTrue(output.getOut().contains("code=400"),output.getOut());
        assertTrue(output.getOut().contains("status=INVALID_ARGUMENT"),output.getOut());
        assertFalse(output.getOut().contains(SECRET));
    }

    private static final String OVERLOADED="{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}";

    // * Answers each attempt with the next event; the last one repeats.
    private int attemptsUntilDone(boolean succeeds, String... events) throws Exception {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt=attempts.incrementAndGet();
            String event=events[Math.min(attempt,events.length)-1];
            String text=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("page",1,"quote","첫 원문 문장이고 정확히 발췌합니다."))+"\n");
            String body=event.equals("ok") ? "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":"+text+"}]}}]}\n\n" : "data: "+event+"\n\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var ai=new StreamingSummaryProvider("gemini","http://127.0.0.1:"+server.getAddress().getPort()+"/models/{model}:generateContent","synthetic","test","",Duration.ofSeconds(3),8,16000,"low");
            var input=new SummaryInput("material","subject",1,"title",SummaryPrompt.VERSION,List.of(new SummaryInput.Page(1,"첫 원문 문장이고 정확히 발췌합니다. "+"이어지는 원문 문장입니다. ".repeat(10),"",null,null)));
            if (succeeds) assertTrue(ai.generate(input,text2 -> {}).content().contains("첫 원문 문장이고 정확히 발췌합니다."));
            else assertEquals(502,assertThrows(ResponseStatusException.class,()->ai.generate(input,text2 -> {})).getStatusCode().value());
        } finally {server.stop(0);}
        return attempts.get();
    }

    @Test void overloadInsideStreamIsRetriedAfterWaiting() throws Exception {
        long started=System.nanoTime();
        assertEquals(2,attemptsUntilDone(true,OVERLOADED,"ok"));
        assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()>=1500,"waits before retrying");
    }

    @Test void continuedOverloadStopsAfterLimitedAttempts() throws Exception {
        assertEquals(4,attemptsUntilDone(false,OVERLOADED));
    }

    @Test void permanentErrorInsideStreamIsNotRetried() throws Exception {
        assertEquals(1,attemptsUntilDone(false,"{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\"}}"));
    }

    @Test void geminiBlockNamesItsReason(CapturedOutput output) throws Exception {
        fail("gemini","{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}");
        assertTrue(output.getOut().contains("blockReason=PROHIBITED_CONTENT"),output.getOut());
    }

    @Test void geminiUnsuccessfulFinishNamesItsReason(CapturedOutput output) throws Exception {
        fail("gemini","{\"candidates\":[{\"finishReason\":\"RECITATION\"}]}");
        assertTrue(output.getOut().contains("finishReason=RECITATION"),output.getOut());
    }

    @Test void openAiFailedEventNamesItsCodeOnly(CapturedOutput output) throws Exception {
        fail("openai","{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\""+SECRET+"\"}}}");
        assertTrue(output.getOut().contains("type=response.failed"),output.getOut());
        assertTrue(output.getOut().contains("code=rate_limit_exceeded"),output.getOut());
        assertFalse(output.getOut().contains(SECRET));
    }

    @Test void openAiIncompleteEventNamesItsReason(CapturedOutput output) throws Exception {
        fail("openai","{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"content_filter\"}}}");
        assertTrue(output.getOut().contains("reason=content_filter"),output.getOut());
    }

    @Test void unexpectedCodeCannotInjectLogText(CapturedOutput output) throws Exception {
        fail("openai","{\"type\":\"error\",\"code\":\"bad\\nforged line "+SECRET+"\"}");
        assertFalse(output.getOut().contains("forged line"));
        assertFalse(output.getOut().contains(SECRET));
    }
}
