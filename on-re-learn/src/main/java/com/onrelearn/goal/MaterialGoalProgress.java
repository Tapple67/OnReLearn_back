package com.onrelearn.goal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record MaterialGoalProgress(int todayCount, int weeklyCount, int todayPercent, int weeklyPercent) {
    private static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Seoul");

    public static MaterialGoalProgress from(GoalView goal, List<Study> studies, Instant now) {
        LocalDate today = now.atZone(STUDY_ZONE).toLocalDate();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        Set<StudyDay> days = new HashSet<>();
        for (Study study : studies) {
            // * The stored Seoul study date is the durable fact, including after material deletion.
            LocalDate date = study.date();
            if (!date.isBefore(monday) && !date.isAfter(today))
                days.add(new StudyDay(study.materialId(), date));
        }
        long daily = days.stream().filter(day -> day.date().equals(today)).count();
        int weekly = days.size();
        return new MaterialGoalProgress((int) daily, weekly,
            percent(daily, goal.dailyMaterialGoal()), percent(weekly, goal.weeklyMaterialGoal()));
    }

    private static int percent(long current, int target) {
        return target <= 0 ? 0 : (int) Math.min(Math.round(current * 100.0 / target), 100);
    }

    public record Study(String materialId, LocalDate date) {}
    private record StudyDay(String materialId, LocalDate date) {}
}
