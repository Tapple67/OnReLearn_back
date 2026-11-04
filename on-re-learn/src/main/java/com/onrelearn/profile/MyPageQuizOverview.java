package com.onrelearn.profile;

import com.onrelearn.quiz.model.QuizAttemptStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record MyPageQuizOverview(int totalQuizCount, int accuracy,
    List<SubjectStat> subjectStats, List<ChartPoint> chartData,
    List<RecentQuiz> recentItems, List<RecentQuiz> studyRecords) {
    private static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM.dd");

    public MyPageQuizOverview {
        subjectStats = List.copyOf(subjectStats);
        chartData = List.copyOf(chartData);
        recentItems = List.copyOf(recentItems);
        studyRecords = List.copyOf(studyRecords);
    }

    public static MyPageQuizOverview from(List<Subject> subjects, List<Attempt> attempts,
            long correctAnswers, long gradedAnswers, Instant now) {
        if (correctAnswers < 0 || gradedAnswers < correctAnswers)
            throw new IllegalArgumentException("Invalid answer counts");
        var completed = attempts.stream()
            .filter(a -> a.status() == QuizAttemptStatus.SUBMITTED || a.status() == QuizAttemptStatus.GRADED)
            .toList();
        int accuracy = gradedAnswers == 0 ? 0 : (int) Math.round(correctAnswers * 100.0 / gradedAnswers);
        var subjectStats = subjects.stream().map(subject -> {
            long score = 0;
            long maxScore = 0;
            for (var attempt : completed) {
                if (subject.id().equals(attempt.subjectId())
                        && attempt.score() != null && attempt.maxScore() != null) {
                    score += attempt.score();
                    maxScore += attempt.maxScore();
                }
            }
            return new SubjectStat(subject.name(), percent(score, maxScore));
        }).toList();
        LocalDate today = now.atZone(STUDY_ZONE).toLocalDate();
        var chart = new ArrayList<ChartPoint>();
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            LocalDate day = today.minusDays(daysAgo);
            long score = 0;
            long maxScore = 0;
            for (var attempt : completed) {
                if (completedAt(attempt).atZone(STUDY_ZONE).toLocalDate().equals(day)) {
                    score += attempt.score() == null ? 0 : attempt.score();
                    maxScore += attempt.maxScore() == null ? 0 : attempt.maxScore();
                }
            }
            chart.add(new ChartPoint(day.format(SHORT_DATE), percent(score, maxScore)));
        }
        // * Study records select by start time; dashboard cards order the selected records by completion time.
        var latestStarted = completed.stream()
            .sorted(Comparator.comparing(Attempt::startedAt).reversed().thenComparing(Attempt::id))
            .limit(8).toList();
        var studyRecords = latestStarted.stream().limit(5).map(MyPageQuizOverview::recent).toList();
        var recentItems = latestStarted.stream()
            .sorted(Comparator.comparing(MyPageQuizOverview::completedAt).reversed().thenComparing(Attempt::id))
            .limit(6).map(MyPageQuizOverview::recent).toList();
        return new MyPageQuizOverview(completed.size(), accuracy, subjectStats, chart,
            recentItems, studyRecords);
    }

    private static Instant completedAt(Attempt attempt) {
        if (attempt.gradedAt() != null) return attempt.gradedAt();
        if (attempt.submittedAt() != null) return attempt.submittedAt();
        return attempt.startedAt();
    }

    private static int percent(long score, long maxScore) {
        return maxScore == 0 ? 0 : (int) Math.round(score * 100.0 / maxScore);
    }

    private static RecentQuiz recent(Attempt attempt) {
        Instant date = completedAt(attempt);
        String title = attempt.quizTitle() == null ? attempt.materialTitle() : attempt.quizTitle();
        String right = attempt.score() == null || attempt.maxScore() == null
            ? "-" : attempt.score() + "/" + attempt.maxScore();
        return new RecentQuiz("퀴즈", attempt.subjectName() + " - " + title,
            right, date.atZone(STUDY_ZONE).format(SHORT_DATE), date);
    }

    public record Subject(String id, String name) {}
    public record Attempt(String id, String subjectId, String subjectName,
        String materialTitle, String quizTitle, QuizAttemptStatus status,
        Integer score, Integer maxScore, Instant startedAt, Instant submittedAt, Instant gradedAt) {}
    public record SubjectStat(String name, int rate) {}
    public record ChartPoint(String label, int rate) {}
    public record RecentQuiz(String type, String title, String right, String date, Instant createdAt) {}
}
