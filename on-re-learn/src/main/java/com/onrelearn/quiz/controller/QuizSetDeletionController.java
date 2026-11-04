package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.QuizSetDeletionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/quiz/quiz-sets")
public class QuizSetDeletionController {
    private final QuizSetDeletionService sets;

    public QuizSetDeletionController(QuizSetDeletionService sets) {
        this.sets = sets;
    }

    @DeleteMapping
    public ResponseEntity<DeletedResponse> delete(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.path("quizSetId").isString())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiz set ID must be a string");
        sets.delete(member.id(), body.get("quizSetId").stringValue());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DeletedResponse(true));
    }

    public record DeletedResponse(boolean ok) {}
}
