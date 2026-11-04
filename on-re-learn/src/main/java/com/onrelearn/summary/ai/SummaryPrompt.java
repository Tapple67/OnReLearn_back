package com.onrelearn.summary.ai;

import com.onrelearn.summary.service.SummaryInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

public final class SummaryPrompt {
    public static final String VERSION = "summary.v19-rewrite-merged";
    private final String template;
    private final JsonMapper json = JsonMapper.builder().build();

    public SummaryPrompt() {
        try (var stream = new ClassPathResource("prompts/summary-v6.txt").getInputStream()) {
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Summary prompt unavailable");
        }
    }

    public String render(SummaryInput input) {
        if (!VERSION.equals(input.promptVersion())) {
            throw new IllegalArgumentException("Summary prompt version mismatch");
        }
        var pages = json.createArrayNode();
        for (var page : input.pages()) {
            var row = pages.addObject();
            row.put("page", page.page());
            row.put("pdfText", page.pdfText());
            // ! Notes steer what is covered; GroundedSummary still accepts PDF quotes only as evidence.
            row.put("note", SummaryNoteLinks.promptSafe(page.note()));
            row.set("noteSignals", page.noteSignals() == null ? json.nullNode() : page.noteSignals());
        }
        // * Formatting arguments are inserted once; placeholder-like user text stays literal.
        return template.formatted(input.week(), input.title(), json.writerWithDefaultPrettyPrinter().writeValueAsString(pages));
    }
}
