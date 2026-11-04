package com.onrelearn.auth.service;

import com.onrelearn.member.repository.MemberRepository;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private final MemberRepository members;
    private final PasswordEncoder passwords;

    public AuthenticationService(MemberRepository members, PasswordEncoder passwords) {
        this.members = members;
        this.passwords = passwords;
    }

    public Optional<String> authenticate(String email, String password) {
        // * 존재하지 않는 이메일과 틀린 비밀번호를 같은 실패 결과로 돌려준다.
        if (email == null || password == null || email.isBlank() || password.isBlank()) {
            return Optional.empty();
        }
        return members.findByEmail(email)
            .filter(member -> passwords.matches(password, member.getPasswordHash()))
            .map(member -> member.getId());
    }
}
