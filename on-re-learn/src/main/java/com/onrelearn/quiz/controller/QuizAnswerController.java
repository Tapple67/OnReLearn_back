package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.QuizAnswerService;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/attempt/answer")
public class QuizAnswerController {
    private final QuizAnswerService answers;
    public QuizAnswerController(QuizAnswerService answers) { this.answers=answers; }

    @PostMapping
    public ResponseEntity<Map<String,Boolean>> save(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody JsonNode body) {
        if(body==null || !body.isObject() || !body.path("attemptId").isString()
                || !body.path("quizItemId").isString())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attempt and item IDs are required");
        // * Preserve JSON false/zero/null selections; domain validation belongs to the service.
        answers.save(member.id(),body.get("attemptId").stringValue(),
                body.get("quizItemId").stringValue(),body.get("response"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ok",true));
    }
}
