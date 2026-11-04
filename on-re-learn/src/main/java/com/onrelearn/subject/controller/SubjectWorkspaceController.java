package com.onrelearn.subject.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.subject.service.SubjectWorkspaceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubjectWorkspaceController {
    private final SubjectWorkspaceService workspace;

    public SubjectWorkspaceController(SubjectWorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/api/subjects/workspace")
    public SubjectWorkspaceService.Workspace view(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "gradeId", required = false) String gradeId,
        @RequestParam(name = "subjectId", required = false) String subjectId) {
        // * 빈 선택값은 기본 학기/과목 선택과 같게 처리한다. 매개변수 이름은 IDE 컴파일 옵션에 의존하지 않는다.
        return workspace.view(member.id(), gradeId == null || gradeId.isBlank() ? null : gradeId,
            subjectId == null || subjectId.isBlank() ? null : subjectId);
    }
}
