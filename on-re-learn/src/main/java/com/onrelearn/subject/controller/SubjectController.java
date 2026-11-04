package com.onrelearn.subject.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.subject.service.SubjectService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/subjects")
public class SubjectController {
    private final SubjectService subjects;

    public SubjectController(SubjectService subjects) {
        this.subjects = subjects;
    }

    @GetMapping
    public List<SubjectService.SubjectSummary> list(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "gradeId", required = false) String gradeId) {
        if (gradeId == null || gradeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "학기 선택이 필요합니다.");
        }
        return subjects.list(member.id(), gradeId.trim());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal CurrentMemberService.CurrentMember member,
                                       @RequestBody(required = false) JsonNode body) {
        // * 기존 API 계약의 이름 길이·학기 ID를 검증한 뒤 Service에서 소유권을 검사한다.
        String name = text(body, "name");
        String gradeId = text(body, "gradeId");
        if (name == null || name.isBlank() || name.trim().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "과목명은 1~50자여야 합니다.");
        }
        if (gradeId == null || gradeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "학기 정보가 필요합니다.");
        }
        return Map.of("ok", true, "subject", subjects.create(member.id(), gradeId.trim(), name.trim()));
    }

    @PatchMapping
    public Map<String, Object> rename(@AuthenticationPrincipal CurrentMemberService.CurrentMember member,
                                       @RequestBody(required = false) JsonNode body) {
        // * 기존 PATCH 계약의 subjectId·name을 검증한 뒤 Service에 소유권 확인을 맡긴다.
        String subjectId = text(body, "subjectId");
        if (subjectId == null || subjectId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectId가 필요합니다.");
        }
        String name = text(body, "name");
        if (name == null || name.isBlank() || name.trim().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "과목명은 1~50자여야 합니다.");
        }
        return Map.of("ok", true, "subject", subjects.rename(member.id(), subjectId.trim(), name.trim()));
    }

    @DeleteMapping
    public Map<String, Object> delete(@AuthenticationPrincipal CurrentMemberService.CurrentMember member,
                                       @RequestBody(required = false) JsonNode body) {
        String subjectId = text(body, "subjectId");
        if (subjectId == null || subjectId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectId가 필요합니다.");
        }
        subjects.delete(member.id(), subjectId.trim());
        return Map.of("ok", true);
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body == null || !body.isObject() ? null : body.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
