package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.service.QuizSetUpdateService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/quiz/quiz-sets")
public class QuizSetUpdateController {
    private final QuizSetUpdateService sets;
    public QuizSetUpdateController(QuizSetUpdateService sets) { this.sets = sets; }

    @PatchMapping
    public ResponseEntity<UpdatedResponse> rename(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody JsonNode body) {
        // ! Reject coercion of numbers/objects into IDs or titles at the HTTP boundary.
        if (body == null || !body.isObject() || !body.path("quizSetId").isString()
                || !body.path("title").isString())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiz set ID and title must be strings");
        var updated = sets.rename(member.id(), body.get("quizSetId").stringValue(), body.get("title").stringValue());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new UpdatedResponse(true, updated));
    }
    public record UpdatedResponse(boolean ok, QuizSetUpdateService.Updated quizSet) {}
}
