package com.onrelearn.goal;

import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.activity.repository.MaterialStudyDayRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoalProgressService {
    private final GoalService goals;
    private final QuizAttemptRepository attempts;
    private final MaterialStudyDayRepository studyDays;

    public GoalProgressService(GoalService goals, QuizAttemptRepository attempts,
                               MaterialStudyDayRepository studyDays) {
        this.goals = goals;
        this.attempts = attempts;
        this.studyDays = studyDays;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuizGoalProgress getQuiz(String userId, Instant now) {
        var goal = goals.get(userId);
        var times = attempts.completionTimesForUser(userId,
            List.of(QuizAttemptStatus.SUBMITTED, QuizAttemptStatus.GRADED));
        return QuizGoalProgress.from(goal, times, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaterialGoalProgress getMaterial(String userId, Instant now) {
        var today = now.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
        var monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        var studies = studyDays.findByUserIdAndStudyDateBetween(userId, monday, today).stream()
            .map(day -> new MaterialGoalProgress.Study(day.getMaterialId(), day.getStudyDate()))
            .toList();
        return MaterialGoalProgress.from(goals.get(userId), studies, now);
    }
}
