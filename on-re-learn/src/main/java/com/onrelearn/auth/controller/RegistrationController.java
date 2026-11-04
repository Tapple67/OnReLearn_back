package com.onrelearn.auth.controller;

import tools.jackson.databind.JsonNode;
import com.onrelearn.auth.service.DuplicateEmailException;
import com.onrelearn.auth.service.RegistrationService;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RegistrationController {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final RegistrationService registration;

    public RegistrationController(RegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody(required = false) JsonNode body) {
        // * 입력 검증 후 회원과 기본 12개 학기를 한 트랜잭션으로 저장한다.
        if (body == null || !body.isObject()) throw badRequest();
        JsonNode emailNode = body.get("email");
        JsonNode passwordNode = body.get("password");
        if (emailNode == null || !emailNode.isString() || passwordNode == null || !passwordNode.isString()) {
            throw badRequest();
        }
        String email = emailNode.stringValue();
        String password = passwordNode.stringValue();
        if (email.length() > 254 || !EMAIL.matcher(email).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "이메일 형식을 확인해 주세요. 앞뒤 공백 없이 name@example.com 형식으로 입력해 주세요.");
        if (password.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 공백만으로 입력할 수 없습니다.");
        try {
            registration.register(email, password);
            return ResponseEntity.ok(new RegisterResponse(true));
        } catch (DataIntegrityViolationException exception) {
            // ! 가입 경합의 unique 실패만 중복 이메일로 바꾸고 다른 DB 오류는 숨기지 않는다.
            if (registration.emailExists(email)) throw new DuplicateEmailException();
            throw exception;
        }
    }

    private ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력 누락 또는 잘못된 형식");
    }

    public record RegisterResponse(boolean ok) {}
}
