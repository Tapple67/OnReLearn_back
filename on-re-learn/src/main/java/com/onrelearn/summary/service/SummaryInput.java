package com.onrelearn.summary.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// * Pure input contract: database ownership checks and provider calls stay in their services.
public record SummaryInput(String materialId, String subjectId, int week, String title,
                           String promptVersion, List<Page> pages) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public SummaryInput {
        Objects.requireNonNull(materialId);
        Objects.requireNonNull(promptVersion);
        pages = List.copyOf(pages);
    }

    public record Text(int page, String text) {}
    public record Note(int page, String content, JsonNode signals, Instant updatedAt) {}
    public record Page(int page, String pdfText, String note, JsonNode noteSignals, Instant noteUpdatedAt) {}
    public record Fingerprint(String sourceHash, String signalsDigest) {
        public boolean matches(String storedHash, String storedSignals) {
            return sourceHash.equals(storedHash) && signalsDigest.equals(storedSignals);
        }
    }

    // * Include note-only pages; input row order must not change the summary source.
    public static SummaryInput assemble(String materialId, String subjectId, int week, String title,
            String promptVersion, List<Text> texts, List<Note> notes) {
        Map<Integer, Text> textByPage = new TreeMap<>();
        Map<Integer, Note> noteByPage = new TreeMap<>();
        SortedSet<Integer> numbers = new TreeSet<>();
        for (Text text : texts) {
            requirePage(text.page());
            if (textByPage.put(text.page(), text) != null) throw new IllegalArgumentException("Duplicate PDF page");
            numbers.add(text.page());
        }
        for (Note note : notes) {
            requirePage(note.page());
            if (noteByPage.put(note.page(), note) != null) throw new IllegalArgumentException("Duplicate note page");
            numbers.add(note.page());
        }
        List<Page> pages = new ArrayList<>();
        for (int number : numbers) {
            Text text = textByPage.get(number);
            Note note = noteByPage.get(number);
            pages.add(new Page(number, trim(text == null ? null : text.text()),
                    trim(note == null ? null : note.content()),
                    note == null || note.signals() == null ? null : note.signals().deepCopy(),
                    note == null ? null : note.updatedAt()));
        }
        return new SummaryInput(materialId, subjectId, week, title, promptVersion, pages);
    }

    public Fingerprint fingerprint() {
        var source = JSON.createObjectNode();
        source.put("materialId", materialId);
        source.put("promptVersion", promptVersion);
        var sourcePages = source.putArray("pages");
        var signals = JSON.createArrayNode();
        for (Page page : pages) {
            var item = sourcePages.addObject();
            item.put("page", page.page());
            item.put("pdfText", page.pdfText());
            item.put("note", page.note());
            item.set("noteSignals", page.noteSignals() == null ? JSON.nullNode() : page.noteSignals());
            var signal = signals.addObject();
            signal.put("page", page.page());
            signal.set("signals", page.noteSignals() == null ? JSON.nullNode() : page.noteSignals());
        }
        // * Title/week/timestamps are excluded, matching the legacy source contract.
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(source).getBytes(StandardCharsets.UTF_8));
            return new Fingerprint(HexFormat.of().formatHex(hash), JSON.writeValueAsString(signals));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void requirePage(int page) {
        if (page < 1) throw new IllegalArgumentException("Page must be positive");
    }

    // * ECMAScript trim includes BOM/NBSP; preserve internal whitespace and note storage.
    private static String trim(String value) {
        return value == null ? "" : value.replaceAll("^[\\s\\p{Zs}\\uFEFF\\u2028\\u2029]+|[\\s\\p{Zs}\\uFEFF\\u2028\\u2029]+$", "");
    }
}
