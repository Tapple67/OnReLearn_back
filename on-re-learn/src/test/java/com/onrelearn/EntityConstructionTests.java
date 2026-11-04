package com.onrelearn;

import com.onrelearn.member.entity.Member;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.subject.entity.Subject;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityConstructionTests {
    @Test
    void builderInitializesIdentityAndKeepsSecretsOutOfLogs() {
        Member member = Member.builder().email("test@example.invalid").passwordHash("secret-hash").build();
        assertNotNull(member.getId());
        assertNotNull(member.getCreatedAt());
        assertFalse(member.toString().contains("secret-hash"));
    }

    @Test
    void builderRetainsDomainValidation() {
        Member member = Member.builder().email("test@example.invalid").passwordHash("hash").build();
        assertThrows(IllegalArgumentException.class,
            () -> Grade.builder().user(member).year(0).term(1).build());
        Grade grade = Grade.builder().user(member).year(1).term(1).build();
        assertThrows(IllegalArgumentException.class,
            () -> Subject.builder().grade(grade).name(" ").build());
        assertEquals("Math", Subject.builder().grade(grade).name(" Math ").build().getName());
    }

    @Test
    void mutableFieldsDoNotChangeEntityHash() {
        Member member = Member.builder().email("before@example.invalid").passwordHash("hash").build();
        var set = new HashSet<Member>();
        set.add(member);
        member.setEmail("after@example.invalid");
        assertTrue(set.contains(member));
    }
}
