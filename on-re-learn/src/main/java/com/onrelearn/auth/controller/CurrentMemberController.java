package com.onrelearn.auth.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CurrentMemberController {
    private final CurrentMemberService currentMembers;

    public CurrentMemberController(CurrentMemberService currentMembers) {
        this.currentMembers = currentMembers;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me(@CookieValue(value = "token", required = false) String token) {
        // * 쿠키의 회원 ID를 DB에서 재조회해 삭제된 계정의 토큰도 거부한다.
        return currentMembers.findByToken(token)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
