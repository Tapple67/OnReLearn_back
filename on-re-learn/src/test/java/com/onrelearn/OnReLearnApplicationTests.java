package com.onrelearn;

import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.UUID;
import java.net.URI;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class OnReLearnApplicationTests {
    // IDE에서 직접 시험해도 SQL 초기화/DataSource 생성 전에 허용된 DB를 확인한다.
    public static class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String url = context.getEnvironment().getRequiredProperty("spring.datasource.url");
            if (!url.startsWith("jdbc:mysql://") || !"/onrelearn".equals(URI.create(url.substring(5)).getPath())) {
                throw new IllegalStateException("MySQL tests require the onrelearn development database");
            }
        }
    }

    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    private Member newMember() {
        return members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash-not-for-login"));
    }

    @Test @Transactional
    void persistsRelationsAndRestrictsLookupToOwner() {
        Member owner = newMember();
        Member other = newMember();
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Database"));
        assertEquals(1, grades.findByUser_IdOrderByYearAscTermAsc(owner.getId()).size());
        assertTrue(subjects.findByIdAndGrade_User_Id(subject.getId(), other.getId()).isEmpty());
        subject.rename("Updated");
        subjects.flush();
        assertEquals("Updated", jdbc.queryForObject("SELECT name FROM subject WHERE id = ?", String.class, subject.getId()));
    }

    @Test @Transactional
    void rejectsDuplicateEmail() {
        Member member = newMember();
        assertThrows(DataIntegrityViolationException.class,
            () -> members.saveAndFlush(new Member(member.getEmail(), "synthetic-hash")));
    }

    @Test @Transactional
    void rejectsDuplicateSemester() {
        Member member = newMember();
        grades.saveAndFlush(new Grade(member, 1, 1, true));
        assertThrows(DataIntegrityViolationException.class,
            () -> grades.saveAndFlush(new Grade(member, 1, 1, false)));
    }

    @Test @Transactional
    void rejectsMissingParent() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO subject (id, grade_id, name, is_current, created_at) VALUES (?, ?, ?, false, UTC_TIMESTAMP(6))",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Invalid parent"));
    }

    @Test
    void rollbackDoesNotLeaveSyntheticRows() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        String id = transaction.execute(status -> {
            Member member = newMember();
            status.setRollbackOnly();
            return member.getId();
        });
        assertFalse(members.existsById(id));
    }

    @Test
    void initializationCanRunAgainWithoutLosingRows() {
        String id = new TransactionTemplate(transactions).execute(status -> newMember().getId());
        try {
            new ResourceDatabasePopulator(new ClassPathResource("sql/onrelearn.sql")).execute(dataSource);
            assertTrue(members.existsById(id));
        } finally {
            members.deleteById(id);
        }
    }
}
