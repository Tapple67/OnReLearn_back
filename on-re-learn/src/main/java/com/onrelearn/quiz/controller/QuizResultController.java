package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.QuizResultService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/attempt/result")
public class QuizResultController {
    private final QuizResultService results;
    public QuizResultController(QuizResultService results) { this.results=results; }
    @GetMapping
    public ResponseEntity<ResultResponse> get(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestParam(name = "attemptId", required=false) String attemptId) {
        // ! The service checks ownership and submission before disclosing answer keys.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ResultResponse(results.get(member.id(),attemptId)));
    }
    public record ResultResponse(QuizResultService.AttemptView attempt) {}
}
