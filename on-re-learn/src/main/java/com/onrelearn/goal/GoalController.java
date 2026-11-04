package com.onrelearn.goal;
import com.onrelearn.auth.service.CurrentMemberService;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
@RestController
@RequestMapping("/api/goals")
public class GoalController {
    private static final Set<String> FIELDS = Set.of(
        "dailyQuizGoal", "weeklyQuizGoal", "dailyMaterialGoal", "weeklyMaterialGoal");
    private final GoalService goals;
    private final GoalProgressService progress;
    public GoalController(GoalService goals, GoalProgressService progress) {
        this.goals = goals;
        this.progress = progress;
    }
    @GetMapping
    public ResponseEntity<GoalResponse> get(@AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new GoalResponse(goals.get(member.id())));
    }
    @GetMapping("/quiz-progress")
    public ResponseEntity<QuizGoalProgress> quizProgress(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(progress.getQuiz(member.id(), java.time.Instant.now()));
    }
    @GetMapping("/material-progress")
    public ResponseEntity<MaterialGoalProgress> materialProgress(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(progress.getMaterial(member.id(), java.time.Instant.now()));
    }
    @PutMapping
    public ResponseEntity<GoalResponse> save(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member, @RequestBody JsonNode body) {
        // ! Reject missing or coerced fields so malformed edits cannot silently overwrite goals.
        if (body == null || !body.isObject() || body.size() != FIELDS.size()) invalid();
        for (String name : body.propertyNames()) if (!FIELDS.contains(name)) invalid();
        var input = new GoalView(number(body, "dailyQuizGoal"), number(body, "weeklyQuizGoal"),
            number(body, "dailyMaterialGoal"), number(body, "weeklyMaterialGoal"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new GoalResponse(goals.save(member.id(), input)));
    }
    private int number(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) invalid();
        return value.intValue();
    }
    private void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "목표 입력이 올바르지 않습니다.");
    }
    public record GoalResponse(GoalView goal) {}
}
