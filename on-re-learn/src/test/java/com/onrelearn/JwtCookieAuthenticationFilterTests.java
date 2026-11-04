package com.onrelearn;

import com.onrelearn.auth.config.JwtCookieAuthenticationFilter;
import com.onrelearn.auth.config.JwtSettings;
import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtCookieAuthenticationFilterTests {
    private static final String SECRET = "synthetic-test-secret-at-least-32-bytes-long";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validCookieMakesExistingMemberTheRequestPrincipal() throws Exception {
        Member member = Member.builder().email("learner@example.invalid").passwordHash("synthetic-hash").build();
        MemberRepository repository = mock(MemberRepository.class);
        when(repository.findById(member.getId())).thenReturn(Optional.of(member));
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3)));
        var filter = new JwtCookieAuthenticationFilter(new CurrentMemberService(tokens, repository));
        var request = new MockHttpServletRequest("GET", "/api/subject");
        request.setServletPath("/api/subject");
        request.setCookies(new Cookie("token", tokens.issue(member.getId())));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var principal = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(principal);
            assertEquals(member.getId(), ((CurrentMemberService.CurrentMember) principal.getPrincipal()).id());
        });
    }

    @Test
    void malformedExpiredTamperedAndDeletedMemberCookiesDoNotAuthenticate() throws Exception {
        MemberRepository repository = mock(MemberRepository.class);
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3)));
        var filter = new JwtCookieAuthenticationFilter(new CurrentMemberService(tokens, repository));
        String expired = Jwts.builder().subject("expired-member")
            .expiration(Date.from(Instant.now().minusSeconds(60)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        String issued = tokens.issue("tampered-member");
        String[] parts = issued.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        String tampered = String.join(".", parts);
        for (String token : new String[] {"malformed", expired, tampered, tokens.issue("deleted-member")}) {
            SecurityContextHolder.clearContext();
            var request = new MockHttpServletRequest("GET", "/api/subject");
            request.setServletPath("/api/subject");
            request.setCookies(new Cookie("token", token));
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
        }
        verify(repository).findById("deleted-member");
    }

    @Test
    void signingConfigurationFailureStopsRequestWithoutAuthentication() throws Exception {
        MemberRepository repository = mock(MemberRepository.class);
        var tokens = new JwtTokenService(new JwtSettings("", Duration.ofHours(3)));
        var filter = new JwtCookieAuthenticationFilter(new CurrentMemberService(tokens, repository));
        var request = new MockHttpServletRequest("GET", "/api/subject");
        request.setServletPath("/api/subject");
        request.setCookies(new Cookie("token", "synthetic-token"));
        var response = new MockHttpServletResponse();
        var reachedController = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> reachedController.set(true));

        assertEquals(503, response.getStatus());
        assertFalse(reachedController.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
