package com.onrelearn.auth.config;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.auth.service.AuthenticationConfigurationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {
    private final CurrentMemberService currentMembers;

    public JwtCookieAuthenticationFilter(CurrentMemberService currentMembers) {
        this.currentMembers = currentMembers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/api/") || path.equals("/api/system/health")
            || path.equals("/api/auth/csrf") || path.equals("/api/auth/login")
            || path.equals("/api/auth/register") || path.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // * HTTP-only 쿠키의 JWT와 현재 회원을 검증한 뒤 SecurityContext에 넣는다.
        String token = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("token".equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        if (token != null) {
            try {
                currentMembers.findByToken(token).ifPresent(member -> {
                    var authentication = new UsernamePasswordAuthenticationToken(member, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            } catch (AuthenticationConfigurationException exception) {
                // ! Servlet 필터는 MVC 예외 처리기보다 먼저 실행되므로 여기서 503을 반환한다.
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
