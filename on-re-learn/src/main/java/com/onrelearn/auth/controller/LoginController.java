package com.onrelearn.auth.controller;

import com.onrelearn.auth.service.AuthenticationService;
import com.onrelearn.auth.service.AuthCookieService;
import com.onrelearn.auth.service.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
public class LoginController {
    private final AuthenticationService authentication;
    private final JwtTokenService tokens;
    private final AuthCookieService cookies;

    public LoginController(AuthenticationService authentication, JwtTokenService tokens,
                           AuthCookieService cookies) {
        this.authentication = authentication;
        this.tokens = tokens;
        this.cookies = cookies;
    }

    // ! JSON만 허용해 다른 사이트의 HTML form이 로그인 세션을 만들지 못하게 한다.
    @PostMapping(path = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        // * 입력 검증 → 자격 증명 확인 → 서명 토큰 발급 → HTTP-only 쿠키 설정 순서다.
        if (body == null || !body.isObject()) throw badRequest();
        JsonNode emailNode = body.get("email");
        JsonNode passwordNode = body.get("password");
        if (emailNode == null || !emailNode.isString() || passwordNode == null || !passwordNode.isString()) {
            throw badRequest();
        }
        String email = emailNode.stringValue();
        String password = passwordNode.stringValue();
        if (email.isBlank() || password.isBlank()) throw badRequest();

        var memberId = authentication.authenticate(email, password);
        if (memberId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 틀렸습니다.");
        }
        String token = tokens.issue(memberId.orElseThrow());
        var cookie = cookies.token(token, request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(new LoginResponse(true));
    }

    private ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력 누락 또는 잘못된 형식");
    }

    public record LoginResponse(boolean ok) {}
}
