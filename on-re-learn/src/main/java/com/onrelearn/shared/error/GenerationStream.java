package com.onrelearn.shared.error;

import com.onrelearn.ai.AiResponseFailure;

import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

public final class GenerationStream {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GenerationStream.class);
    private GenerationStream() {}
    public static void run(HttpServletResponse response, Function<Consumer<Map<String,Object>>,Object> action) throws IOException {
        response.setContentType("text/event-stream"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store"); response.setHeader("X-Accel-Buffering","no");
        var writer=response.getWriter(); var json=JsonMapper.builder().build();
        var stage = new java.util.concurrent.atomic.AtomicReference<>("preparing");
        Consumer<Map<String,Object>> send = value -> {
            if ("stage".equals(value.get("type")) && value.get("value") instanceof String next) stage.set(next);
            writer.write("data: "+json.writeValueAsString(value)+"\n\n"); writer.flush();
            if (writer.checkError()) throw new java.io.UncheckedIOException(new IOException("Stream disconnected"));
        };
        try { send.accept(Map.of("type","stage","value","preparing")); send.accept(Map.of("type","done","value",action.apply(send))); }
        catch (UncheckedIOException disconnected) { /* ! Stop work when the client is gone. */ }
        catch (Exception exception) {
            // ! Headers may already be committed; emit a safe failure event at this streaming boundary.
            int status=exception instanceof ResponseStatusException expected ? expected.getStatusCode().value() : 500;
            LOG.warn("Generation failed: stage={}, status={}, type={}", stage.get(), status, exception.getClass().getSimpleName());
            var failure = new java.util.LinkedHashMap<String, Object>();
            failure.put("type", "error");
            failure.put("status", status);
            failure.put("stage", stage.get());
            failure.put("message", status==504 ? "AI 응답 대기 시간이 초과됐습니다." : "생성을 완료하지 못했습니다. 저장된 결과를 확인해 주세요.");
            if (exception instanceof AiResponseFailure ai) {
                failure.put("code", ai.code().name());
                if (ai.upstreamStatus() != null) failure.put("upstreamStatus", ai.upstreamStatus());
                LOG.warn("AI generation detail: code={}, upstreamStatus={}", ai.code(), ai.upstreamStatus());
            }
            send.accept(failure);
        }
    }
}
