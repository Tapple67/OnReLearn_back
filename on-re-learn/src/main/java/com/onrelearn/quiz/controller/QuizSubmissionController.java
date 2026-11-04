package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.QuizSubmissionService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/attempt/submit")
public class QuizSubmissionController {
    private final QuizSubmissionService submissions;
    public QuizSubmissionController(QuizSubmissionService submissions) { this.submissions=submissions; }

    @PostMapping
    public ResponseEntity<SubmitResponse> submit(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody JsonNode body) {
        if(body==null || !body.isObject() || !body.path("attemptId").isString())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attempt ID is required");
        var result=submissions.submit(member.id(),body.get("attemptId").stringValue());
        // * Only return the committed score, never answer keys or persistence entities.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new SubmitResponse(true,result.attemptId(),result.score(),result.maxScore()));
    }
    public record SubmitResponse(boolean ok,String attemptId,int score,int maxScore) {}
}
