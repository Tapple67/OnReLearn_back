package com.onrelearn;
import com.onrelearn.ai.StreamingSummaryProvider;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInput;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSummaryTests {
    @Test void emitsActualDeltasAndRequiresCompletionForBothProviders() throws Exception {
        for (String provider:List.of("openai","gemini")) for (int completionAttempt:List.of(1,2,3)) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var sent=new java.util.concurrent.atomic.AtomicReference<String>();
            var attempts=new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/", exchange -> {
                boolean complete=attempts.incrementAndGet()==completionAttempt;
                sent.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String raw=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("page",1,"quote","첫 원문 문장이고 정확히 발췌합니다."))+"\n";
                String encoded=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(raw);
                String first=provider.equals("gemini") ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"+encoded+"}]}}]}" : "{\"type\":\"response.output_text.delta\",\"delta\":"+encoded+"}";
                String last=provider.equals("gemini") ? "{\"candidates\":[{\"finishReason\":\"STOP\"}]}" : "{\"type\":\"response.completed\"}";
                byte[] bytes=("data: "+first+"\n\n"+(complete ? "data: "+last+"\n\n" : "")).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            try {
                var ai=new StreamingSummaryProvider(provider,"http://127.0.0.1:"+server.getAddress().getPort()+"/models/{model}:generateContent","synthetic","test","",Duration.ofSeconds(3),8,16000,"low");
                var input=new SummaryInput("material","subject",1,"title",SummaryPrompt.VERSION,List.of(new SummaryInput.Page(1,"첫 원문 문장이고 정확히 발췌합니다.","",null,null)));
                var chunks=new ArrayList<String>();
                if (completionAttempt<=2) assertTrue(ai.generate(input,chunks::add).content().contains("첫 원문 문장이고 정확히 발췌합니다."));
                else assertEquals(502,assertThrows(ResponseStatusException.class,()->ai.generate(input,chunks::add)).getStatusCode().value());
                assertEquals(Math.min(completionAttempt,2),attempts.get());
                assertEquals(1,chunks.size());
                assertTrue(chunks.get(0).contains("핵심 정리"));
                assertEquals(provider.equals("gemini"),sent.get().contains("thinkingLevel"));
                if (provider.equals("gemini")) assertTrue(sent.get().contains("LOW"));
            } finally {server.stop(0);}
        }
    }
    @Test void longDocumentIsStreamedInSmallOrderedGroupsWithoutDroppingPages() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/",exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            int page=(requests.size()-1)*8+1;
            String raw=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("page",page,"quote","PAGE_"+page+"_PDF 원문 사실 문장입니다."))+"\n";
            String encoded=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(raw);
            String payload="data: {\"type\":\"response.output_text.delta\",\"delta\":"+encoded+"}\n\ndata: {\"type\":\"response.completed\"}\n\n";
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var pages=new ArrayList<SummaryInput.Page>();
            for(int n=1;n<=78;n++)pages.add(new SummaryInput.Page(n,"PAGE_"+n+"_PDF 원문 사실 문장입니다.","PAGE_"+n+"_NOTE",null,null));
            var ai=new StreamingSummaryProvider("openai","http://127.0.0.1:"+server.getAddress().getPort(),"synthetic","test","",Duration.ofSeconds(3));
            var early=new ArrayList<Integer>();
            var result=ai.generate(new SummaryInput("material","subject",1,"title",SummaryPrompt.VERSION,pages),text -> early.add(requests.size()));
            assertEquals(10,requests.size());assertEquals(1,early.get(0));
            // * 제목을 정하지 못한 묶음들은 같은 절로 이어 붙여 "핵심 정리"를 반복하지 않는다.
            assertEquals(1,result.content().split("핵심 정리",-1).length-1);
            for(int n=1;n<=78;n++) {
                String all=String.join("",requests);
                assertEquals(1,all.split("PAGE_"+n+"_PDF",-1).length-1);
                assertEquals(1,all.split("PAGE_"+n+"_NOTE",-1).length-1);
            }
            assertFalse(requests.get(0).contains("PAGE_9_PDF"));
        } finally {server.stop(0);}
    }

    @Test void retriesEmptyBatchAndUsesOnlyOriginalSlideText() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes();
            attempts.incrementAndGet();
            byte[] body="data: {\"type\":\"response.completed\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String source="암호 기술의 주요 구성 요소와 적용 사례";
            var ai=new StreamingSummaryProvider("openai","http://127.0.0.1:"+server.getAddress().getPort(),"synthetic","test","",Duration.ofSeconds(3));
            var input=new SummaryInput("material","subject",1,"title",SummaryPrompt.VERSION,
                    List.of(new SummaryInput.Page(9,source,"",null,null)));
            var chunks=new ArrayList<String>();
            var result=ai.generate(input,chunks::add);
            assertEquals(2,attempts.get());
            assertEquals(1,chunks.size());
            assertEquals(chunks.get(0),result.content());
            assertTrue(result.content().contains("원문 발췌 · "+source+" (p.9)"));
        } finally { server.stop(0); }
    }
}
