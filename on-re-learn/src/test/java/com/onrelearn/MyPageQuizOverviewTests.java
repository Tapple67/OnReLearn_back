package com.onrelearn;

import com.onrelearn.profile.MyPageQuizOverview;
import com.onrelearn.profile.MyPageQuizOverview.Attempt;
import com.onrelearn.profile.MyPageQuizOverview.Subject;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MyPageQuizOverviewTests {
    @Test void completedQuizSummaryAndSeoulChartKeepLegacyRecentSelection() {
        Instant now = Instant.parse("2026-10-19T03:00:00Z");
        var subjects = List.of(new Subject("a", "수학"), new Subject("b", "과학"), new Subject("empty", "국어"));
        var attempts = List.of(
            attempt("a1", "a", "수학", "자료 A", "퀴즈 A", QuizAttemptStatus.GRADED,
                70, 100, "2026-10-19T00:00:00Z", "2026-10-19T01:00:00Z"),
            attempt("b1", "b", "과학", "자료 B", null, QuizAttemptStatus.GRADED,
                null, null, "2026-10-18T13:00:00Z", "2026-10-19T02:00:00Z"),
            attempt("a2", "a", "수학", "자료 A", "옛 퀴즈", QuizAttemptStatus.SUBMITTED,
                20, 50, "2026-10-10T00:00:00Z", "2026-10-10T01:00:00Z"),
            attempt("draft", "a", "수학", "자료 A", "미완료", QuizAttemptStatus.IN_PROGRESS,
                null, null, "2026-10-19T02:30:00Z", null));

        var result = MyPageQuizOverview.from(subjects, attempts, 1, 2, now);
        assertEquals(3, result.totalQuizCount());
        assertEquals(50, result.accuracy());
        assertEquals(List.of(new MyPageQuizOverview.SubjectStat("수학", 60),
            new MyPageQuizOverview.SubjectStat("과학", 0),
            new MyPageQuizOverview.SubjectStat("국어", 0)), result.subjectStats());
        assertEquals(7, result.chartData().size());
        assertEquals(new MyPageQuizOverview.ChartPoint("10.19", 70), result.chartData().get(6));
        assertEquals(new MyPageQuizOverview.ChartPoint("10.18", 0), result.chartData().get(5));
        assertEquals(List.of("과학 - 자료 B", "수학 - 퀴즈 A", "수학 - 옛 퀴즈"),
            result.recentItems().stream().map(MyPageQuizOverview.RecentQuiz::title).toList());
        assertEquals(List.of("수학 - 퀴즈 A", "과학 - 자료 B", "수학 - 옛 퀴즈"),
            result.studyRecords().stream().map(MyPageQuizOverview.RecentQuiz::title).toList());
        assertEquals("-", result.recentItems().get(0).right());
        assertEquals("70/100", result.recentItems().get(1).right());
    }

    @Test void emptyQuizHistoryHasZeroAccuracyAndSevenEmptyDays() {
        var result = MyPageQuizOverview.from(List.of(new Subject("a", "수학")), List.of(),
            0, 0, Instant.parse("2026-10-19T03:00:00Z"));
        assertEquals(0, result.totalQuizCount());
        assertEquals(0, result.accuracy());
        assertEquals(List.of(new MyPageQuizOverview.SubjectStat("수학", 0)), result.subjectStats());
        assertEquals(7, result.chartData().size());
        assertTrue(result.chartData().stream().allMatch(day -> day.rate() == 0));
        assertTrue(result.recentItems().isEmpty());
        assertTrue(result.studyRecords().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MyPageQuizOverview.from(List.of(), List.of(), 2, 1,
            Instant.parse("2026-10-19T03:00:00Z")));
    }

    private static Attempt attempt(String id, String subjectId, String subjectName,
            String materialTitle, String quizTitle, QuizAttemptStatus status, Integer score,
            Integer maxScore, String startedAt, String completedAt) {
        Instant finished = completedAt == null ? null : Instant.parse(completedAt);
        return new Attempt(id, subjectId, subjectName, materialTitle, quizTitle, status,
            score, maxScore, Instant.parse(startedAt), finished, finished);
    }
}
