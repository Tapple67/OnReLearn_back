package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.AttemptAlreadySubmittedException;
import com.onrelearn.quiz.service.QuizAttemptStartService;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attempt/start")
public class QuizAttemptStartController {
    private final QuizAttemptStartService attempts;
    public QuizAttemptStartController(QuizAttemptStartService attempts) { this.attempts=attempts; }

    @PostMapping
    public ResponseEntity<StartResponse> start(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody Map<String,Object> body) {
        if(body==null || !(body.get("quizSetId") instanceof String id))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Quiz set ID is required");
        var result=attempts.start(member.id(),id);
        // * Preserve the legacy completed-attempt redirect contract through common advice.
        if("already-submitted".equals(result.status())) throw new AttemptAlreadySubmittedException(result.attemptId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new StartResponse(result.attemptId(),result.quizSet(),result.existingAnswers()));
    }

    public record StartResponse(String attemptId,QuizAttemptStartService.SetView quizSet,
                                List<QuizAttemptStartService.AnswerView> existingAnswers) {}
}
