package com.onrelearn;

import com.onrelearn.auth.config.JwtSettings;
import com.onrelearn.auth.controller.CurrentMemberController;
import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.shared.error.ApiExceptionHandler;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrentMemberTests {
    private static final String SECRET = "synthetic-test-secret-at-least-32-bytes-long";

    @Test
    void validCookieReturnsOnlyCurrentMemberIdentity() throws Exception {
        MemberRepository repository = mock(MemberRepository.class);
        Member member = Member.builder().email("learner@example.invalid").passwordHash("synthetic-hash").build();
        when(repository.findById(member.getId())).thenReturn(Optional.of(member));
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3)));
        var mvc = MockMvcBuilders.standaloneSetup(new CurrentMemberController(new CurrentMemberService(tokens, repository)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        String body = mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("token", tokens.issue(member.getId()))))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"id\":\"" + member.getId() + "\",\"email\":\"learner@example.invalid\"}"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("password"));
    }

    @Test
    void absentInvalidAndDeletedMemberCookiesAreUnauthorized() throws Exception {
        MemberRepository repository = mock(MemberRepository.class);
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3)));
        var mvc = MockMvcBuilders.standaloneSetup(new CurrentMemberController(new CurrentMemberService(tokens, repository)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("token", "invalid")))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("token", tokens.issue("deleted-id"))))
            .andExpect(status().isUnauthorized());
        verify(repository).findById("deleted-id");
        verify(repository, never()).findById("invalid");
    }

    @Test
    void missingSigningKeyReportsServerUnavailable() throws Exception {
        MemberRepository repository = mock(MemberRepository.class);
        var tokens = new JwtTokenService(new JwtSettings("", Duration.ofHours(3)));
        var mvc = MockMvcBuilders.standaloneSetup(new CurrentMemberController(new CurrentMemberService(tokens, repository)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("token", "some-token")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"error\":\"인증 설정을 확인해 주세요.\"}"));
        verify(repository, never()).findById(anyString());
    }
}
