package com.onrelearn;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.subject.controller.SubjectWorkspaceController;
import com.onrelearn.subject.service.SubjectWorkspaceService;
import com.onrelearn.shared.error.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubjectWorkspaceControllerTests {
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    @Test void bindsNamedAndBlankSelectionsEvenWithoutCompilerParameterNames() throws Exception {
        var service = mock(SubjectWorkspaceService.class);
        var empty = new SubjectWorkspaceService.Workspace(List.of(), List.of(), null, null);
        when(service.view("owner", null, null)).thenReturn(empty);
        when(service.view("owner", "grade-a", "subject-a")).thenReturn(empty);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentMemberService.CurrentMember("owner", "synthetic@example.invalid"), null, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new SubjectWorkspaceController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/subjects/workspace").param("gradeId", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.grades").isArray());
        mvc.perform(get("/api/subjects/workspace").param("gradeId", "grade-a").param("subjectId", "subject-a"))
            .andExpect(status().isOk());
        verify(service).view("owner", null, null);
        verify(service).view("owner", "grade-a", "subject-a");
    }
}
