package com.onrelearn.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {
    private final boolean secureByConfig;

    public AuthCookieService(@Value("${onrelearn.auth.cookie.secure:false}") boolean secureByConfig) {
        this.secureByConfig = secureByConfig;
    }

    public ResponseCookie token(String jwt, HttpServletRequest request) {
        // ! JWT는 JavaScript에서 읽지 못하도록 HttpOnly로 발급한다.
        return ResponseCookie.from("token", jwt)
            .httpOnly(true).secure(secureByConfig || request.isSecure())
            .sameSite("Lax").path("/").build();
    }

    public ResponseCookie clearToken(HttpServletRequest request) {
        // * 로그인 쿠키와 같은 Path·SameSite·Secure 정책으로 만료시켜 브라우저에서 제거한다.
        return ResponseCookie.from("token", "")
            .httpOnly(true).secure(secureByConfig || request.isSecure())
            .sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
    }

    public ResponseCookie clearCsrf(HttpServletRequest request) {
        return ResponseCookie.from("XSRF-TOKEN", "")
            .httpOnly(false).secure(secureByConfig || request.isSecure())
            .sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
    }
}
