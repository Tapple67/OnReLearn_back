package com.onrelearn;

import com.onrelearn.quiz.ai.QuizContext;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizContextTests {
    private SummaryInput packet(List<SummaryInput.Text> texts, List<SummaryInput.Note> notes) {
        return SummaryInput.assemble("material", "subject", 1, "title", "summary.v6", texts, notes);
    }
    private List<SummaryInput.Text> pdfs() {
        return List.of(new SummaryInput.Text(6,"six"), new SummaryInput.Text(1,"one"),
                new SummaryInput.Text(2,"two"), new SummaryInput.Text(3,"three"),
                new SummaryInput.Text(4,"four"), new SummaryInput.Text(5,"five"));
    }
    @Test void selectsNoteNeighborsAndPreferredNeighborsWithoutLosingNoteOnlyPages() {
        var signals = JsonMapper.builder().build().readTree("{\"confused\":true}");
        var packet = packet(pdfs(), List.of(new SummaryInput.Note(3,"private\n  note", signals,null),
                new SummaryInput.Note(8,"note-only", null,null)));
        String fingerprint = packet.fingerprint().sourceHash();
        var context = QuizContext.assemble("summary", packet, List.of(6,6));
        assertEquals(List.of(2,3,4,5,6,8), context.sourcePages().stream().map(QuizContext.SourcePage::page).toList());
        assertEquals(List.of(3,8), context.notes().stream().map(QuizContext.Note::page).toList());
        assertEquals("private\n  note", context.notes().get(0).note());
        assertEquals(signals, context.notes().get(0).signals());
        assertNotSame(packet.pages().get(2).noteSignals(), context.notes().get(0).signals());
        assertEquals("", context.sourcePages().get(5).pdfText());
        assertEquals(fingerprint, packet.fingerprint().sourceHash());
        assertFalse(context.toString().contains("private"));
        assertFalse(context.notes().toString().contains("private"));
        assertThrows(UnsupportedOperationException.class, () -> context.notes().clear());
    }
    @Test void fallsBackToThreeNonemptyPdfPagesOnlyWhenNoPagesAreWanted() {
        var packet = packet(pdfs(), List.of());
        assertEquals(List.of(1,2,3), QuizContext.assemble("summary", packet, List.of())
                .sourcePages().stream().map(QuizContext.SourcePage::page).toList());
        assertTrue(QuizContext.assemble("summary", packet, List.of(99)).sourcePages().isEmpty());
        assertTrue(QuizContext.assemble("summary", packet(List.of(), List.of()), List.of()).sourcePages().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> QuizContext.assemble("summary",packet,List.of(0)));
        assertTrue(QuizContext.assemble("summary",packet,List.of(Integer.MAX_VALUE)).sourcePages().isEmpty());
    }
    @Test void acceptsReviewEvidencePagesWithoutNarrowingOrFallback() {
        var json = JsonMapper.builder().build();
        var focus = new com.onrelearn.quiz.ai.QuizFocus(com.onrelearn.quiz.ai.QuizFocus.Kind.WRONG_REVIEW,
                List.of("Topic"), List.of(new com.onrelearn.quiz.ai.QuizFocus.WrongItem("Q", "Topic", null,
                json.readTree("{\"page\":\"5\"}"))));
        var source = new com.onrelearn.quiz.service.QuizWrongReviewService.Source(focus, List.of("item"));
        var input = packet(pdfs(), List.of());
        assertEquals(List.of(4,5,6), QuizContext.assemble("summary", input, source.preferredPages())
                .sourcePages().stream().map(QuizContext.SourcePage::page).toList());
        var boundary = packet(List.of(new SummaryInput.Text(Integer.MAX_VALUE,"last")), List.of());
        assertEquals(Integer.MAX_VALUE, QuizContext.assemble("summary", boundary, List.of(2147483648d))
                .sourcePages().get(0).page());
        for (double page : List.of(2147483649d, 1e100, Double.MAX_VALUE))
            assertTrue(QuizContext.assemble("summary", input, List.of(page)).sourcePages().isEmpty());
        for (double page : List.of(0d, -1d, 1.5d, Double.NaN, Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException.class, () -> QuizContext.assemble("summary", input, List.of(page)));
    }
    @Test void normalizesOnlyPdfWhitespaceAndMatchesLegacyLengthBoundary() {
        var packet = packet(List.of(new SummaryInput.Text(1,"a\u00a0\uFEFF\n b"),
                new SummaryInput.Text(2,"x".repeat(2000)),
                new SummaryInput.Text(3,"x".repeat(2001))), List.of());
        var context = QuizContext.assemble(" summary unchanged ", packet, List.of());
        assertEquals(" summary unchanged ", context.summary());
        assertEquals("a b", context.sourcePages().get(0).pdfText());
        assertEquals("x".repeat(2000), context.sourcePages().get(1).pdfText());
        assertEquals("x".repeat(2000)+"...", context.sourcePages().get(2).pdfText());
    }
}
