package com.onrelearn;

import com.onrelearn.goal.GoalView;
import com.onrelearn.goal.QuizGoalProgress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuizGoalProgressTests {
    @Test void seoulDayAndMondayBoundaryWithZeroAndCappedGoals() {
        var now = Instant.parse("2026-10-15T01:00:00Z");
        var times = List.of(
            Instant.parse("2026-10-14T14:59:59Z"),
            Instant.parse("2026-10-14T15:00:00Z"),
            Instant.parse("2026-10-15T00:59:59Z"),
            Instant.parse("2026-10-11T14:59:59Z"),
            Instant.parse("2026-10-11T15:00:00Z"));
        assertEquals(new QuizGoalProgress(2, 4, 0, 100),
            QuizGoalProgress.from(new GoalView(0, 2, 3, 15), times, now));
        assertEquals(new QuizGoalProgress(2, 4, 67, 100),
            QuizGoalProgress.from(new GoalView(3, 4, 3, 15), times, now));
    }
}
