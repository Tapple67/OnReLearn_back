package com.onrelearn.auth.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/auth/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        token.getToken(); // Force deferred token creation and the XSRF-TOKEN cookie.
        return new CsrfResponse(token.getHeaderName());
    }

    public record CsrfResponse(String headerName) {}
}
