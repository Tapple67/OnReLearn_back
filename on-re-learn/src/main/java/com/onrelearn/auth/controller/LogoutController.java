package com.onrelearn.auth.controller;

import com.onrelearn.auth.service.AuthCookieService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogoutController {
    private final AuthCookieService cookies;

    public LogoutController(AuthCookieService cookies) {
        this.cookies = cookies;
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<LoginController.LoginResponse> logout(HttpServletRequest request) {
        // * 토큰이 만료됐어도 두 쿠키를 지워 사용자가 다시 로그인할 수 있게 한다.
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE,
                cookies.clearToken(request).toString(), cookies.clearCsrf(request).toString())
            .body(new LoginController.LoginResponse(true));
    }
}
