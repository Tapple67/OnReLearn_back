package com.onrelearn.goal;
import com.onrelearn.goal.entity.Goal;
import com.onrelearn.goal.repository.GoalRepository;
import com.onrelearn.member.repository.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
@Service
public class GoalService {
    private final GoalRepository goals;
    private final MemberRepository members;
    public GoalService(GoalRepository goals, MemberRepository members) {
        this.goals = goals;
        this.members = members;
    }
    @Transactional(readOnly = true)
    public GoalView get(String userId) {
        requireUser(userId);
        return goals.findByUser_Id(userId).map(GoalView::from).orElse(GoalView.DEFAULT);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GoalView save(String userId, GoalView input) {
        requireUser(userId);
        if (input == null || input.dailyQuizGoal() < 0 || input.weeklyQuizGoal() < 0
                || input.dailyMaterialGoal() < 0 || input.weeklyMaterialGoal() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "목표는 0 이상의 정수여야 합니다.");
        // * Serialize first saves for one owner with the existing member row lock.
        var member = members.findByIdForUpdate(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        var goal = goals.findByUser_Id(userId).orElseGet(() -> new Goal(member));
        goal.update(input.dailyQuizGoal(), input.weeklyQuizGoal(), input.dailyMaterialGoal(), input.weeklyMaterialGoal());
        return GoalView.from(goals.saveAndFlush(goal));
    }
    private void requireUser(String userId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
}
