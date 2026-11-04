package com.onrelearn;

import com.onrelearn.auth.service.DuplicateEmailException;
import com.onrelearn.auth.service.AuthenticationService;
import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.auth.config.JwtSettings;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.repository.GradeRepository;
import java.util.UUID;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RegistrationMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired PasswordEncoder passwords;
    @Autowired AuthenticationService authentication;

    @Test
    @Transactional
    void registersMemberAndTwelveDefaultGradesAtomically() {
        String email = UUID.randomUUID() + "@example.invalid";
        registration.register(email, "synthetic-password");

        Member member = members.findByEmail(email).orElseThrow();
        assertNotEquals("synthetic-password", member.getPasswordHash());
        assertTrue(passwords.matches("synthetic-password", member.getPasswordHash()));
        assertEquals(member.getId(), authentication.authenticate(email, "synthetic-password").orElseThrow());
        assertTrue(authentication.authenticate(email, "wrong-password").isEmpty());
        assertTrue(authentication.authenticate("missing@example.invalid", "synthetic-password").isEmpty());
        var defaults = grades.findByUser_IdOrderByYearAscTermAsc(member.getId());
        assertEquals(12, defaults.size());
        assertEquals(1, defaults.stream().filter(grade -> grade.isCurrent()).count());
        assertTrue(defaults.get(0).isCurrent());
        assertEquals(1, defaults.get(0).getYear());
        assertEquals(1, defaults.get(0).getTerm());
        assertThrows(DuplicateEmailException.class, () -> registration.register(email, "another-password"));
    }

    @Test
    @Transactional
    void currentMemberLookupUsesSignedCookieIdentityAndRealMysqlRow() {
        String email = UUID.randomUUID() + "@example.invalid";
        registration.register(email, "synthetic-password");
        Member member = members.findByEmail(email).orElseThrow();
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(
            "synthetic-test-secret-at-least-32-bytes-long", Duration.ofHours(3)));
        CurrentMemberService current = new CurrentMemberService(tokens, members);

        var identity = current.findByToken(tokens.issue(member.getId())).orElseThrow();
        assertEquals(member.getId(), identity.id());
        assertEquals(email, identity.email());
        assertTrue(current.findByToken(tokens.issue("missing-member-id")).isEmpty());
    }
}
