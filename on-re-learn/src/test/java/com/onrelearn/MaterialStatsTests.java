package com.onrelearn;

import com.onrelearn.stats.MaterialStats;
import com.onrelearn.stats.MaterialStats.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialStatsTests {
    private final Material material = new Material("m", "자료", 2, "과목", new Grade(2026, 1));

    @Test void distinguishesNoSubmissionsFromCompletedWithoutAnswers() {
        var empty = MaterialStats.build(material, List.of(new QuizSet("GENERAL", List.of(),
            List.of(new Attempt("open", "IN_PROGRESS", 90, 100, null, List.of())))));
        assertEquals(new Summary(0, 0, 0, 0, 0, 0, 0, 0), empty.summary());
        assertEquals("NONE", empty.understanding().level());
        assertEquals(List.of("먼저 이 자료에 대해 퀴즈를 풀어보세요."), empty.recommendations());
        assertTrue(empty.trend().isEmpty());
        assertEquals(3, empty.typeStats().size());
        assertEquals(3, empty.purposeStats().size());
        var completed = MaterialStats.build(material, List.of(new QuizSet(null, List.of(),
            List.of(new Attempt("done", "SUBMITTED", null, null, null, List.of())))));
        assertEquals(new Summary(1, 0, 0, 0, 0, 0, 0, 0), completed.summary());
        assertEquals("LOW", completed.understanding().level());
        assertEquals(1, completed.purposeStats().get("GENERAL").attempts());
        assertEquals(new Trend("done", null, 0, 0, 0, "GENERAL"), completed.trend().get(0));
        assertSame(material, completed.material());
    }

    @Test void aggregatesAllCompletedAnswersAndKeepsRawScoreMeaning() {
        var items = List.of(new Item("m", "mcq", " A "), new Item("t", "tf", "B"),
            new Item("s", "short", "C"), new Item("x", "unknown", "X"));
        var answers = new ArrayList<>(List.of(new Answer("m", true), new Answer("t", false),
            new Answer("s", null), new Answer("x", true), new Answer("missing", true)));
        var first = new Attempt("a", "GRADED", 2, 6, Instant.parse("2026-10-10T00:00:00Z"), answers);
        answers.clear();
        var result = MaterialStats.build(material, List.of(
            new QuizSet("GENERAL", items, List.of(first)),
            new QuizSet("WRONG_REVIEW", items, List.of(new Attempt("b", "SUBMITTED", 1, 3, null,
                List.of(new Answer("m", false), new Answer("t", null), new Answer("s", false))))),
            new QuizSet("WEAK_TOPIC", items, List.of(new Attempt("c", "GRADED", null, null, null, List.of())))));
        assertEquals(new Summary(3, 6, 1, 5, 16.7, 1, 2, 2), result.summary());
        assertEquals(new Accuracy(2, 1, 1, 50), result.typeStats().get("mcq"));
        assertEquals(new Accuracy(2, 0, 2, 0), result.typeStats().get("tf"));
        assertEquals(new Purpose(1, 3, 1, 2, 33.3), result.purposeStats().get("GENERAL"));
        assertEquals(new Purpose(1, 3, 0, 3, 0), result.purposeStats().get("WRONG_REVIEW"));
        assertEquals(new Purpose(1, 0, 0, 0, 0), result.purposeStats().get("WEAK_TOPIC"));
        assertEquals(33.3, result.trend().get(2).accuracy());
        assertEquals(List.of("B", "C", "A"), result.weakTopics().stream().map(t -> t.topic()).toList());
        assertEquals(List.of("이 자료는 전반적으로 다시 점검하는 것이 좋습니다.",
            "서술형 문제에서 개념 설명이 부족할 수 있습니다.",
            "객관식 문제에서 선지 구분 연습이 필요합니다.",
            "OX 문제에서 기본 개념 구분을 다시 확인해보세요.",
            "\"B\" 주제를 점검해보세요."), result.recommendations());
        assertNull(first.answers().get(2).isCorrect());
        assertThrows(UnsupportedOperationException.class, () -> result.typeStats().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.trend().clear());
    }

    @Test void b10UsesGlobalSubmissionOrderWithDeterministicNullAndTieOrdering() {
        var early = Instant.parse("2026-10-10T00:00:00Z");
        var late = Instant.parse("2026-10-11T00:00:00Z");
        var attempts = new ArrayList<>(List.of(
            new Attempt("z", "GRADED", 99, 100, late, List.of()),
            new Attempt("b", "GRADED", 10, 100, early, List.of()),
            new Attempt("a", "SUBMITTED", 20, 100, early, List.of())));
        var sets = List.of(new QuizSet("unexpected", List.of(), attempts),
            new QuizSet("GENERAL", List.of(), List.of(
                new Attempt("legacy", "GRADED", 5, 0, null, List.of()))));
        var result = MaterialStats.build(material, sets);
        assertEquals(List.of("legacy", "a", "b", "z"), result.trend().stream().map(Trend::attemptId).toList());
        assertEquals(99, result.summary().latestScore());
        assertEquals(99, result.summary().bestScore());
        assertEquals(33.5, result.summary().averageScore());
        assertEquals("GENERAL", result.trend().get(3).purpose());
        assertEquals(List.of("z", "b", "a"), attempts.stream().map(Attempt::id).toList());
    }

    @Test void understandingUsesRoundedAccuracyThresholds() {
        int[] correctCounts = {0, 49, 50, 69, 70, 84, 85, 100};
        var levels = List.of("LOW", "LOW", "MID_LOW", "MID_LOW", "MID_HIGH", "MID_HIGH", "HIGH", "HIGH");
        for (int n = 0; n < correctCounts.length; n++) {
            var items = new ArrayList<Item>();
            var answers = new ArrayList<Answer>();
            for (int i = 0; i < 100; i++) {
                items.add(new Item("i" + i, "mcq", "A"));
                answers.add(new Answer("i" + i, i < correctCounts[n]));
            }
            var result = MaterialStats.build(material, List.of(new QuizSet("GENERAL", items,
                List.of(new Attempt("a", "GRADED", correctCounts[n], 100, null, answers)))));
            assertEquals(levels.get(n), result.understanding().level());
            if (correctCounts[n] == 100) assertTrue(result.recommendations().isEmpty());
        }
    }
}
