package com.onrelearn;

import com.onrelearn.summary.service.SummaryInput;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SummaryInputTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private SummaryInput input(String text, String note, String version, String signals, Instant time) {
        return SummaryInput.assemble("m", "s", 1, "title", version,
                List.of(new SummaryInput.Text(1, text)),
                List.of(new SummaryInput.Note(2, note, json.readTree(signals), time)));
    }

    @Test void joinsPagesAndMatchesLegacyJsonHash() {
        var input = input(" \uFEFFPDF\u00A0", " Note ", "summary.v6", "{\"difficulty\":\"high\"}", Instant.EPOCH);
        assertEquals(List.of(1, 2), input.pages().stream().map(SummaryInput.Page::page).toList());
        assertEquals("", input.pages().get(0).note());
        assertEquals("", input.pages().get(1).pdfText());
        assertEquals("73e59b512f84d3da2dd378a05dea2f466e3a0f07b06b0b32360ffbeca33fb8f1", input.fingerprint().sourceHash());
        assertEquals("[{\"page\":1,\"signals\":null},{\"page\":2,\"signals\":{\"difficulty\":\"high\"}}]", input.fingerprint().signalsDigest());
        var reordered = SummaryInput.assemble("m", "other", 9, "renamed", "summary.v6",
                List.of(new SummaryInput.Text(1, "PDF")),
                List.of(new SummaryInput.Note(2, "Note", json.readTree("{\"difficulty\":\"high\"}"), Instant.now())));
        assertEquals(input.fingerprint(), reordered.fingerprint());
        var sorted = SummaryInput.assemble("m", "s", 1, "t", "v", List.of(new SummaryInput.Text(3, "x"), new SummaryInput.Text(1, "y")), List.of());
        assertEquals(List.of(1, 3), sorted.pages().stream().map(SummaryInput.Page::page).toList());
    }

    @Test void contentSignalsAndPromptChangesInvalidateReuse() {
        var original = input("PDF", "Note", "v1", "null", Instant.EPOCH).fingerprint();
        assertNotEquals(original, input("changed", "Note", "v1", "null", Instant.EPOCH).fingerprint());
        assertNotEquals(original, input("PDF", "changed", "v1", "null", Instant.EPOCH).fingerprint());
        assertNotEquals(original, input("PDF", "Note", "v2", "null", Instant.EPOCH).fingerprint());
        assertNotEquals(original, input("PDF", "Note", "v1", "{}", Instant.EPOCH).fingerprint());
        assertTrue(original.matches(original.sourceHash(), original.signalsDigest()));
        assertFalse(original.matches(original.sourceHash(), null));
        assertFalse(original.matches("other", original.signalsDigest()));
    }

    @Test void handlesEmptyInputAndRejectsInvalidOrDuplicatePages() {
        assertTrue(SummaryInput.assemble("m", "s", 1, "t", "v", List.of(), List.of()).pages().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SummaryInput.assemble("m", "s", 1, "t", "v", List.of(new SummaryInput.Text(0, "")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> SummaryInput.assemble("m", "s", 1, "t", "v", List.of(new SummaryInput.Text(1, ""), new SummaryInput.Text(1, "")), List.of()));
    }
}
