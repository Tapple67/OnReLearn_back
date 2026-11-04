package com.onrelearn.summary.ai;

import com.onrelearn.summary.service.SummaryInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

public final class SummaryPrompt {
    public static final String VERSION = "summary.v22-rewrite-sparse-pages";
    private static final int MAX_OVERVIEW_INPUT = 30000;
    private final String template;
    private final String overviewTemplate;
    private final JsonMapper json = JsonMapper.builder().build();

    public SummaryPrompt() {
        template = resource("prompts/summary-v6.txt");
        overviewTemplate = resource("prompts/summary-overview.txt");
    }

    private static String resource(String path) {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Summary prompt unavailable");
        }
    }

    public String overview(String title, String summary) {
        String input = summary.length() > MAX_OVERVIEW_INPUT ? summary.substring(0, MAX_OVERVIEW_INPUT) : summary;
        return overviewTemplate.formatted(title, input);
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
