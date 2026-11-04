package com.onrelearn.quiz.ai;

import com.onrelearn.summary.service.SummaryInput;
import com.onrelearn.summary.ai.SummaryNoteLinks.Focus;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

// * Pure context selection; callers must check ownership and summary freshness first.
public record QuizContext(String summary, List<Note> notes, List<SourcePage> sourcePages, List<Focus> memoFocus) {
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Zs}\\uFEFF\\u2028\\u2029]+");
    private static final int PDF_LIMIT = 2000;

    public QuizContext {
        Objects.requireNonNull(summary);
        notes = List.copyOf(notes);
        sourcePages = List.copyOf(sourcePages);
        memoFocus = List.copyOf(memoFocus);
    }

    public QuizContext(String summary, List<Note> notes, List<SourcePage> sourcePages) {
        this(summary, notes, sourcePages, List.of());
    }

    public record Note(int page, String note, JsonNode signals) {
        @Override public String toString() { return "QuizContext.Note[content omitted]"; }
    }
    public record SourcePage(int page, String pdfText, String note, JsonNode signals) {
        @Override public String toString() { return "QuizContext.SourcePage[content omitted]"; }
    }

    public static QuizContext assemble(String summary, SummaryInput packet, List<? extends Number> preferredPages) {
        Set<Double> wanted = new HashSet<>();
        for (Number value : preferredPages) {
            double page = value.doubleValue();
            if (!Double.isFinite(page) || page < 1 || page != Math.floor(page))
                throw new IllegalArgumentException("Page must be a finite positive integer");
            addNeighbors(wanted, page);
        }
        List<Note> notes = new ArrayList<>();
        for (var page : packet.pages()) {
            if (!normalized(page.note()).isEmpty()) {
                addNeighbors(wanted, page.page());
                notes.add(new Note(page.page(), page.note(), copy(page.noteSignals())));
            }
        }
        // * Preserve packet order. An unmatched preferred page does not trigger the PDF fallback.
        List<SourcePage> sources = new ArrayList<>();
        for (var page : packet.pages()) {
            boolean hasPdf = !normalized(page.pdfText()).isEmpty();
            boolean hasNote = !normalized(page.note()).isEmpty();
            boolean selected = wanted.isEmpty() ? hasPdf && sources.size() < 3
                    : wanted.contains((double) page.page());
            if (selected && page.page() > 0 && (hasPdf || hasNote)) {
                sources.add(new SourcePage(page.page(), truncate(page.pdfText()),
                        page.note(), copy(page.noteSignals())));
            }
        }
        return new QuizContext(summary, notes, sources);
    }

    private static void addNeighbors(Set<Double> wanted, double page) {
        // * Match JavaScript Number arithmetic without narrowing review evidence to int.
        wanted.add(page - 1);
        wanted.add(page);
        wanted.add(page + 1);
    }
    private static String normalized(String value) {
        return SPACE.matcher(value).replaceAll(" ").trim();
    }
    private static String truncate(String value) {
        String text = normalized(value);
        // * Match JavaScript's UTF-16 slice and append the legacy ellipsis.
        return text.length() <= PDF_LIMIT ? text : text.substring(0, PDF_LIMIT) + "...";
    }
    private static JsonNode copy(JsonNode value) { return value == null ? null : value.deepCopy(); }

    // ! Summary and notes may contain private study content.
    @Override public String toString() { return "QuizContext[content omitted]"; }
}
