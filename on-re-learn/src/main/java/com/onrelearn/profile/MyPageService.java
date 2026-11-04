package com.onrelearn.profile;

import com.onrelearn.auth.service.CurrentMemberService.CurrentMember;
import com.onrelearn.goal.GoalProgressService;
import com.onrelearn.goal.GoalService;
import com.onrelearn.goal.GoalView;
import com.onrelearn.goal.QuizGoalProgress;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyPageService {
    private final GoalService goals;
    private final GoalProgressService progress;
    private final MyPageQuizService quizzes;

    public MyPageService(GoalService goals, GoalProgressService progress, MyPageQuizService quizzes) {
        this.goals = goals;
        this.progress = progress;
        this.quizzes = quizzes;
    }

    // * Existing read services join one snapshot so saved goals and their progress agree in this response.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(CurrentMember member, Instant now) {
        String userId = member.id();
        return new View(member.email(), goals.get(userId), progress.getQuiz(userId, now),
            quizzes.get(userId, now));
    }

    public record View(String email, GoalView goal, QuizGoalProgress quizProgress,
        MyPageQuizOverview quiz) {}
}
