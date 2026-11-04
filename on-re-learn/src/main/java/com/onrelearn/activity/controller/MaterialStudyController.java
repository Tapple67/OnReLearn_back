package com.onrelearn.activity.controller;

import com.onrelearn.activity.service.MaterialStudyService;
import com.onrelearn.auth.service.CurrentMemberService;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/activity/material-study")
public class MaterialStudyController {
    private final MaterialStudyService study;

    public MaterialStudyController(MaterialStudyService study) { this.study = study; }

    @PostMapping
    public ResponseEntity<StudyResponse> rendered(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody JsonNode body) {
        // ! Only the material ID is accepted; user and study time come from the server.
        if (body == null || !body.isObject() || body.size() != 1
            || body.get("materialId") == null || !body.get("materialId").isString()
            || body.get("materialId").asString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자료 ID가 올바르지 않습니다.");
        }
        study.record(member.id(), body.get("materialId").asString(), Instant.now());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new StudyResponse(true));
    }

    public record StudyResponse(boolean ok) {}
}
