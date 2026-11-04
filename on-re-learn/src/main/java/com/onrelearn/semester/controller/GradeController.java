package com.onrelearn.semester.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.semester.service.CurrentGradeService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/grades")
public class GradeController {
    private final CurrentGradeService grades;

    public GradeController(CurrentGradeService grades) {
        this.grades = grades;
    }

    @GetMapping
    public List<CurrentGradeService.GradeSummary> list(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        return grades.list(member.id());
    }

    @PatchMapping
    public Map<String, Object> change(@AuthenticationPrincipal CurrentMemberService.CurrentMember member,
                                 @RequestBody(required = false) JsonNode body) {
        // * 기존 API처럼 gradeId를 받아 선택하고 같은 학기면 unchanged를 응답한다.
        JsonNode id = body == null || !body.isObject() ? null : body.get("gradeId");
        if (id == null || !id.isString() || id.stringValue().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gradeId가 필요합니다.");
        }
        return grades.select(member.id(), id.stringValue())
            ? Map.of("ok", true)
            : Map.of("ok", true, "unchanged", true);
    }
}
