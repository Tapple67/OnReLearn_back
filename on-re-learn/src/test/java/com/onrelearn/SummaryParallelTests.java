package com.onrelearn;

import com.onrelearn.ai.StreamingSummaryProvider;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInput;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SummaryParallelTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern FIRST_PAGE = Pattern.compile("PAGE_(\\d+)_PDF");

    private static String pdf(int page) { return "PAGE_" + page + "_PDF 원문 사실 문장입니다."; }
    private static SummaryInput input(int pages) {
        var rows = new ArrayList<SummaryInput.Page>();
        for (int page = 1; page <= pages; page++) rows.add(new SummaryInput.Page(page, pdf(page), "", null, null));
        return new SummaryInput("material", "subject", 1, "title", SummaryPrompt.VERSION, rows);
    }
    private static StreamingSummaryProvider provider(HttpServer server, int concurrency) {
        return new StreamingSummaryProvider("openai", "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "test", "",
            Duration.ofSeconds(5), 2, 16000, "", concurrency);
    }
    private static int firstPage(String body) {
        var match = FIRST_PAGE.matcher(body);
        assertTrue(match.find());
        return Integer.parseInt(match.group(1));
    }
    private static void reply(HttpExchange exchange, int page) throws java.io.IOException {
        String raw = JSON.writeValueAsString(Map.of("page", page, "quote", pdf(page))) + "\n";
        String payload = "data: {\"type\":\"response.output_text.delta\",\"delta\":" + JSON.writeValueAsString(raw) + "}\n\ndata: {\"type\":\"response.completed\"}\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static List<Integer> pagesIn(String text) {
        var pages = new ArrayList<Integer>();
        var match = FIRST_PAGE.matcher(text);
        while (match.find()) pages.add(Integer.parseInt(match.group(1)));
        return pages;
    }

    @Test void batchesRunTogetherAndAreStillPublishedInOrder() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int page = firstPage(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try { Thread.sleep(page == 1 ? 400 : 60); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
            reply(exchange, page);
        });
        server.start();
        try {
            var chunks = new ArrayList<String>();
            var result = provider(server, 3).generate(input(12), chunks::add);
            assertTrue(peak.get() > 1, "동시 요청 " + peak.get());
            assertTrue(peak.get() <= 3, "동시 요청 " + peak.get());
            assertEquals(List.of(1, 3, 5, 7, 9, 11), pagesIn(String.join("", chunks)));
            assertEquals(List.of(1, 3, 5, 7, 9, 11), pagesIn(result.content()));
            assertEquals(String.join("", chunks), result.content());
        } finally { server.stop(0); }
    }
    @Test void aFailedRequestIsRetriedWithoutChangingTheOrder() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var failedOnce = new AtomicInteger();
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int page = firstPage(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (page == 3 && failedOnce.getAndIncrement() == 0) { exchange.sendResponseHeaders(429, -1); exchange.close(); return; }
            reply(exchange, page);
        });
        server.start();
        try {
            var result = provider(server, 3).generate(input(6));
            assertEquals(List.of(1, 3, 5), pagesIn(result.content()));
            assertEquals(2, failedOnce.get());
        } finally { server.stop(0); }
    }
    @Test void aBatchThatKeepsFailingFailsTheWholeSummary() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThrows(ResponseStatusException.class, () -> provider(server, 3).generate(input(6)));
        } finally { server.stop(0); }
    }
    @Test void aSingleWorkerSendsOneRequestAtATime() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int page = firstPage(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try { Thread.sleep(40); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
            reply(exchange, page);
        });
        server.start();
        try {
            provider(server, 1).generate(input(6));
            assertEquals(1, peak.get());
        } finally { server.stop(0); }
    }
    @Test void invalidConcurrencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StreamingSummaryProvider("openai", "http://127.0.0.1:1", "k", "m", "", Duration.ofSeconds(1), 2, 16000, "", 0));
        assertThrows(IllegalArgumentException.class, () -> new StreamingSummaryProvider("openai", "http://127.0.0.1:1", "k", "m", "", Duration.ofSeconds(1), 2, 16000, "", 11));
    }
}
