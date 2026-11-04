package com.onrelearn.goal;
import com.onrelearn.goal.entity.Goal;
public record GoalView(int dailyQuizGoal, int weeklyQuizGoal, int dailyMaterialGoal, int weeklyMaterialGoal) {
    public static final GoalView DEFAULT = new GoalView(10, 70, 3, 15);
    public static GoalView from(Goal g) {
        return new GoalView(g.getDailyQuizGoal(), g.getWeeklyQuizGoal(), g.getDailyMaterialGoal(), g.getWeeklyMaterialGoal());
    }
}
