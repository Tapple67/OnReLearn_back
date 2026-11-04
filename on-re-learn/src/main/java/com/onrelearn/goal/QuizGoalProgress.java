package com.onrelearn.goal;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public record QuizGoalProgress(int todayCount, int weeklyCount, int todayPercent, int weeklyPercent) {
    private static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Seoul");

    public static QuizGoalProgress from(GoalView goal, List<Instant> completedAt, Instant now) {
        var today = now.atZone(STUDY_ZONE).toLocalDate();
        var start = today.atStartOfDay(STUDY_ZONE).toInstant();
        var tomorrow = today.plusDays(1).atStartOfDay(STUDY_ZONE).toInstant();
        var monday = today.minusDays(today.getDayOfWeek().getValue() - 1L)
            .atStartOfDay(STUDY_ZONE).toInstant();
        int daily = 0;
        int weekly = 0;
        // * Completion time follows gradedAt, submittedAt, then startedAt in the caller's projection.
        for (Instant time : completedAt) {
            if (!time.isBefore(start) && time.isBefore(tomorrow)) daily++;
            if (!time.isBefore(monday)) weekly++;
        }
        return new QuizGoalProgress(daily, weekly,
            percent(daily, goal.dailyQuizGoal()), percent(weekly, goal.weeklyQuizGoal()));
    }

    private static int percent(int current, int target) {
        return target <= 0 ? 0 : (int) Math.min(Math.round(current * 100.0 / target), 100);
    }
}
