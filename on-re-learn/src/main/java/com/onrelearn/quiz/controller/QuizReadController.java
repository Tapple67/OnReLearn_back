package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.QuizReadService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz/quiz-sets")
public class QuizReadController {
    private final QuizReadService quizzes;
    public QuizReadController(QuizReadService quizzes) { this.quizzes = quizzes; }

    @GetMapping
    public ResponseEntity<ListResponse> list(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestParam(name = "materialId", required = false) String materialId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ListResponse(quizzes.list(member.id(), materialId)));
    }

    public record ListResponse(List<QuizReadService.SetView> quizSets) {}
}
