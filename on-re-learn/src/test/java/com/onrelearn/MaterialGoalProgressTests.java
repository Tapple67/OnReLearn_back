package com.onrelearn;

import com.onrelearn.goal.GoalView;
import com.onrelearn.goal.MaterialGoalProgress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialGoalProgressTests {
    @Test void countsDistinctMaterialsPerSeoulDayAndSumsAcrossWeek() {
        var now = Instant.parse("2026-10-22T00:30:00Z"); // Thursday 09:30 in Seoul.
        var studies = List.of(
            new MaterialGoalProgress.Study("a", LocalDate.of(2026, 10, 22)),
            new MaterialGoalProgress.Study("a", LocalDate.of(2026, 10, 22)),
            new MaterialGoalProgress.Study("b", LocalDate.of(2026, 10, 22)),
            new MaterialGoalProgress.Study("a", LocalDate.of(2026, 10, 21)),
            new MaterialGoalProgress.Study("old", LocalDate.of(2026, 10, 18)),
            new MaterialGoalProgress.Study("future", LocalDate.of(2026, 10, 23)));
        var goal = new GoalView(10, 70, 3, 15);
        assertEquals(new MaterialGoalProgress(2, 3, 67, 20), MaterialGoalProgress.from(goal, studies, now));
        assertEquals(new MaterialGoalProgress(2, 3, 0, 100),
            MaterialGoalProgress.from(new GoalView(10, 70, 0, 1), studies, now));
    }
}
