package com.onrelearn;

import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.service.SummaryInput;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SummaryContractTests {
    @Test void rendersSourceAndSignalsWithoutInternalIdentifiersOrTimestamps() {
        var input = SummaryInput.assemble("private-material-id", "private-subject-id", 3, "Literal %s ${input.week}",
                SummaryPrompt.VERSION, List.of(new SummaryInput.Text(1, "PDF source")),
                List.of(new SummaryInput.Note(1, "Private note", JsonMapper.builder().build().readTree("{\"difficulty\":\"high\"}"), Instant.EPOCH)));
        String prompt = new SummaryPrompt().render(input);
        assertTrue(prompt.contains("Literal %s ${input.week}"));
        assertTrue(prompt.contains("PDF source"));
        assertTrue(prompt.contains("Private note"));
        assertTrue(prompt.contains("difficulty"));
        assertTrue(prompt.contains("high"));
        assertFalse(prompt.contains("private-material-id"));
        assertFalse(prompt.contains("private-subject-id"));
        assertFalse(prompt.contains("1970-01-01"));
        assertFalse(prompt.contains("noteUpdatedAt"));
        assertThrows(IllegalArgumentException.class, () -> new SummaryPrompt().render(
                SummaryInput.assemble("m", "s", 1, "t", "wrong", List.of(), List.of())));
    }

    @Test void retainsLegacySingleOutputAndDistinctProviderVariants() {
        var single = new SummaryResult("Markdown", null, null, "test", "model", SummaryPrompt.VERSION);
        assertEquals("Markdown", single.canonical());
        assertEquals("Markdown", single.adaptive());
        var distinct = new SummaryResult("Original", "General", "Personal", "test", "model", SummaryPrompt.VERSION);
        assertEquals("General", distinct.canonical());
        assertEquals("Personal", distinct.adaptive());
        assertFalse(distinct.toString().contains("Personal"));
    }

    @Test void rejectsBlankOutputAndMissingMetadataWithSafeUpstreamError() {
        for (String output : new String[] {null, "", " \n "}) {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> new SummaryResult(output, null, null, "test", "model", SummaryPrompt.VERSION));
            assertEquals(502, failure.getStatusCode().value());
            assertEquals("Invalid summary response", failure.getReason());
        }
        assertThrows(ResponseStatusException.class, () -> new SummaryResult("private", " ", null, "test", "model", SummaryPrompt.VERSION));
        assertThrows(ResponseStatusException.class, () -> new SummaryResult("private", null, "", "test", "model", SummaryPrompt.VERSION));
        assertThrows(ResponseStatusException.class, () -> new SummaryResult("private", null, null, "test", null, SummaryPrompt.VERSION));
    }
}
