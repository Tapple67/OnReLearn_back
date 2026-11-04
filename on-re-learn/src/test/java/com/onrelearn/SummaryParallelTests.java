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
    private static HttpServer withOverview(String goals, AtomicInteger overviewCalls, int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("학습 목표를 작성")) { reply(exchange, firstPage(body)); return; }
            overviewCalls.incrementAndGet();
            if (status != 200) { exchange.sendResponseHeaders(status, -1); exchange.close(); return; }
            String payload = "data: {\"type\":\"response.output_text.delta\",\"delta\":" + JSON.writeValueAsString(goals) + "}\n\ndata: {\"type\":\"response.completed\"}\n\n";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
    private static StreamingSummaryProvider withOverview(HttpServer server) {
        return new StreamingSummaryProvider("openai", "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "test", "",
            Duration.ofSeconds(5), 2, 16000, "", 3, true);
    }
    private static final String SPARSE_HEADING = "## 원문에서 확인할 쪽";
    private static String fullPage(int page) { return pdf(page) + " 본문 글자가 충분한 쪽입니다.".repeat(10); }
    private static SummaryInput pages(String... texts) {
        var rows = new ArrayList<SummaryInput.Page>();
        for (int index = 0; index < texts.length; index++) rows.add(new SummaryInput.Page(index + 1, texts[index], "", null, null));
        return new SummaryInput("material", "subject", 1, "title", SummaryPrompt.VERSION, rows);
    }
    @Test void pagesWithLittleTextAreListedAfterTheSummary() throws Exception {
        var server = withOverview("", new AtomicInteger(), 200);
        try {
            var chunks = new ArrayList<String>();
            String content = withOverview(server).generate(pages(fullPage(1), "그림 1", "", fullPage(4)), chunks::add).content();
            assertTrue(content.endsWith(SPARSE_HEADING + "\n\n글자가 적은 쪽입니다. 그림이나 표의 내용이 요약에 빠졌을 수 있으니 원문에서 확인해 주세요.\n\n(p.2) (p.3)\n\n"), content);
            assertTrue(String.join("", chunks).contains(SPARSE_HEADING), "생성 중 미리보기에도 보여 준다");
        } finally { server.stop(0); }
    }
    @Test void whitespaceDoesNotCountAsTextOnAPage() throws Exception {
        var server = withOverview("", new AtomicInteger(), 200);
        try {
            String padded = pdf(1) + " ".repeat(200) + "끝";
            assertTrue(withOverview(server).generate(pages(padded)).content().contains(SPARSE_HEADING), "공백을 빼면 글자가 적은 쪽이다");
        } finally { server.stop(0); }
    }
    @Test void aDocumentFullOfTextHasNoSparsePageSection() throws Exception {
        var server = withOverview("", new AtomicInteger(), 200);
        try {
            assertFalse(withOverview(server).generate(pages(fullPage(1), fullPage(2))).content().contains(SPARSE_HEADING));
        } finally { server.stop(0); }
    }
    @Test void learningGoalsAreNotWrittenFromTheSparsePageSection() throws Exception {
        var overviewBodies = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("학습 목표를 작성")) { reply(exchange, firstPage(body)); return; }
            synchronized (overviewBodies) { overviewBodies.add(body); }
            String goal = JSON.writeValueAsString("{\"goal\":\"PAGE_1_PDF 원문 사실 문장을 설명할 수 있다.\"}\n");
            byte[] bytes = ("data: {\"type\":\"response.output_text.delta\",\"delta\":" + goal + "}\n\ndata: {\"type\":\"response.completed\"}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String content = withOverview(server).generate(pages(fullPage(1), fullPage(2), pdf(3))).content();
            assertTrue(content.startsWith("## 학습 목표"), content);
            assertTrue(content.indexOf(SPARSE_HEADING) > content.indexOf("## 1."), content);
            assertEquals(1, overviewBodies.size());
            assertFalse(overviewBodies.get(0).contains("원문에서 확인할 쪽"), "목표는 요약 내용으로만 만든다");
        } finally { server.stop(0); }
    }
    @Test void learningGoalsAreCheckedAndPlacedAboveTheSummary() throws Exception {
        String goals = "{\"goal\":\"PAGE_1_PDF 원문 사실 문장을 설명할 수 있다.\"}\n"
            + "{\"goal\":\"양자 컴퓨터의 광자 연산을 설명할 수 있다.\"}\n"
            + "{\"goal\":\"PAGE_3_PDF 원문 사실 99가지를 나열할 수 있다.\"}\n";
        var server = withOverview(goals, new AtomicInteger(), 200);
        try {
            var chunks = new ArrayList<String>();
            String content = withOverview(server).generate(input(4), chunks::add).content();
            assertTrue(content.startsWith("## 학습 목표\n\n- PAGE_1_PDF 원문 사실 문장을 설명할 수 있다.\n\n"), content);
            assertFalse(content.contains("양자"), content);
            assertFalse(content.contains("99가지"), content);
            assertTrue(content.indexOf("학습 목표") < content.indexOf("## 1."), content);
            assertFalse(String.join("", chunks).contains("학습 목표"), "미리보기에는 넣지 않는다");
        } finally { server.stop(0); }
    }
    @Test void goalsAreLimitedToFour() throws Exception {
        var goals = new StringBuilder();
        for (int n = 1; n <= 8; n++) goals.append("{\"goal\":\"PAGE_1_PDF 원문 사실 문장 목표").append(" 설명 ".repeat(n)).append("\"}\n");
        var server = withOverview(goals.toString(), new AtomicInteger(), 200);
        try {
            String content = withOverview(server).generate(input(4)).content();
            assertEquals(4, content.lines().filter(line -> line.startsWith("- PAGE_1_PDF 원문 사실 문장 목표")).count(), content);
        } finally { server.stop(0); }
    }
    @Test void aFailedOverviewKeepsTheSummary() throws Exception {
        var calls = new AtomicInteger();
        var server = withOverview("", calls, 400);
        try {
            String content = withOverview(server).generate(input(4)).content();
            assertEquals(1, calls.get());
            assertTrue(content.startsWith("## 1."), content);
        } finally { server.stop(0); }
    }
    @Test void noUsableGoalLeavesNoEmptyHeading() throws Exception {
        var server = withOverview("{\"goal\":\"양자 컴퓨터의 광자 연산을 설명할 수 있다.\"}\n잘못된 줄\n", new AtomicInteger(), 200);
        try {
            assertFalse(withOverview(server).generate(input(4)).content().contains("학습 목표"));
        } finally { server.stop(0); }
    }
    @Test void aShortMaterialDoesNotSpendAnExtraCall() throws Exception {
        var calls = new AtomicInteger();
        var server = withOverview("", calls, 200);
        try {
            withOverview(server).generate(input(2));
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
    @Test void invalidConcurrencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StreamingSummaryProvider("openai", "http://127.0.0.1:1", "k", "m", "", Duration.ofSeconds(1), 2, 16000, "", 0));
        assertThrows(IllegalArgumentException.class, () -> new StreamingSummaryProvider("openai", "http://127.0.0.1:1", "k", "m", "", Duration.ofSeconds(1), 2, 16000, "", 11));
    }
}
