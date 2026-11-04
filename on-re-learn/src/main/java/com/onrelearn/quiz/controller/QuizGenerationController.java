package com.onrelearn.quiz.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.quiz.model.QuizPurpose;
import tools.jackson.databind.json.JsonMapper;
import com.onrelearn.quiz.service.QuizGenerationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/quiz/quiz-sets")
public class QuizGenerationController {
    private final QuizGenerationService quizzes;
    private final JsonMapper json = JsonMapper.builder().build();

    public QuizGenerationController(QuizGenerationService quizzes) { this.quizzes = quizzes; }

    @PostMapping
    public ResponseEntity<CreatedResponse> generate(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody Map<String, Object> body) {
        return generateWithProgress(member,body,null);
    }
    private ResponseEntity<CreatedResponse> generateWithProgress(CurrentMemberService.CurrentMember member, Map<String,Object> body, java.util.function.Consumer<String> stage) {
        if (body == null || !(body.get("materialId") instanceof String materialId) || materialId.isBlank()) {
            throw invalid("Material ID is required");
        }
        QuizPurpose purpose = QuizPurpose.GENERAL;
        if (body.get("purpose") != null) {
            if (!(body.get("purpose") instanceof String value)) throw invalid("Invalid quiz purpose");
            try { purpose = QuizPurpose.valueOf(value); }
            catch (IllegalArgumentException unknown) { throw invalid("Invalid quiz purpose"); }
        }
        Object attempt = body.get("sourceAttemptId");
        Object topics = body.get("sourceTopics");
        if (attempt != null && !(attempt instanceof String)) throw invalid("Invalid review source ID");
        if (topics != null && !(topics instanceof List<?>)) throw invalid("Invalid review topics");
        String attemptId = attempt == null ? null : ((String) attempt).trim();
        // ! Do not silently turn a review request into a general quiz.
        if (purpose == QuizPurpose.GENERAL && ((attemptId != null && !attemptId.isEmpty())
                || (topics instanceof List<?> list && !list.isEmpty())))
            throw invalid("Review inputs are not supported for GENERAL quizzes");
        if (purpose == QuizPurpose.WRONG_REVIEW && (attemptId == null || attemptId.isEmpty()))
            throw invalid("Review source ID is required");
        if (purpose == QuizPurpose.WEAK_TOPIC && attemptId != null && !attemptId.isEmpty())
            throw invalid("Weak topic review does not use a source attempt");
        QuizSpec spec = parseSpec(body.get("spec"));
        var topicNode = json.valueToTree(topics);
        var created = stage==null ? quizzes.generate(member.id(), materialId.trim(), spec, purpose, attemptId, topicNode) : quizzes.generate(member.id(), materialId.trim(), spec, purpose, attemptId, topicNode,stage);
        // ! Return identifiers only; answer keys belong to the later grading flow.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CreatedResponse(
                true, created.quizSetId(), created.createdAt(), created.summaryId(), created.sourceHash()));
    }

    @PostMapping("/stream")
    public void stream(@AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody Map<String,Object> body, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        com.onrelearn.shared.error.GenerationStream.run(response, events -> generateWithProgress(member,body,
                stage -> events.accept(Map.of("type","stage","value",stage))).getBody());
    }

    private static QuizSpec parseSpec(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> spec)) throw invalid("Invalid quiz counts");
        if (!(spec.get("mcqCount") instanceof Integer mcq)
                || !(spec.get("tfCount") instanceof Integer tf)
                || !(spec.get("shortCount") instanceof Integer shortCount)) {
            throw invalid("Quiz counts must be integers");
        }
        try { return new QuizSpec(mcq, tf, shortCount); }
        catch (IllegalArgumentException invalidSpec) { throw invalid("Invalid quiz counts"); }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CreatedResponse(boolean ok, String quizSetId, Instant createdAt,
                                  String summaryId, String sourceHash) {}
}
