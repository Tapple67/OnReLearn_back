package com.onrelearn;

import com.onrelearn.auth.service.AuthenticationService;
import com.onrelearn.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServiceTests {
    @Test
    void missingCredentialsNeverReachDatabase() {
        MemberRepository members = mock(MemberRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        AuthenticationService authentication = new AuthenticationService(members, passwords);

        assertTrue(authentication.authenticate(null, "password").isEmpty());
        assertTrue(authentication.authenticate("learner@example.invalid", null).isEmpty());
        assertTrue(authentication.authenticate(" ", "password").isEmpty());
        assertTrue(authentication.authenticate("learner@example.invalid", " ").isEmpty());
        verifyNoInteractions(members, passwords);
    }
}
