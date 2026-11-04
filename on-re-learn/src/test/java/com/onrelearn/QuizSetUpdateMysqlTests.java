package com.onrelearn;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.quiz.entity.*;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.*;
import com.onrelearn.quiz.service.QuizSetUpdateService;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class QuizSetUpdateMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @MockitoSpyBean QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;
    @Autowired QuizSetUpdateService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test void renamesOnlyOwnedSetAndRollsBackFailedWrite() {
        String email = UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            var owner = members.saveAndFlush(new Member(email, "synthetic"));
            var grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
            var subject = subjects.saveAndFlush(new Subject(grade, "Rename"));
            var material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/test.pdf"));
            materialId = material.getId();
            var set = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash")
                .title("Original").purpose(QuizPurpose.GENERAL).build());
            var item = items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("tf")
                .question("Question").answerKey("{\"correct\":false}").points(100).build());
            var attempt = attempts.saveAndFlush(new QuizAttempt(owner.getId(), set));
            var answer = answers.saveAndFlush(new QuizAnswer(attempt, item, "{\"value\":false}"));
            jdbc.update("UPDATE quiz_attempt SET status='GRADED',score=0,max_score=100 WHERE id=?", attempt.getId());
            var history = jdbc.queryForMap("SELECT * FROM quiz_attempt WHERE id=?", attempt.getId());
            var response = jdbc.queryForMap("SELECT * FROM quiz_answer WHERE id=?", answer.getId());
            assertStatus(401, () -> service.rename(null, set.getId(), "Title"));
            assertStatus(400, () -> service.rename(owner.getId(), " ", "Title"));
            String emoji = new String(Character.toChars(0x1F600));
            for (String title : new String[]{null, "", " \u00a0\uFEFF\n", "가".repeat(101), emoji.repeat(51)})
                assertStatus(400, () -> service.rename(owner.getId(), set.getId(), title));
            assertStatus(403, () -> service.rename("other", set.getId(), "Forbidden"));
            assertStatus(403, () -> service.rename(owner.getId(), "missing", "Missing"));
            assertEquals("Original", title(set.getId()));
            var result = service.rename(owner.getId(), " " + set.getId() + " ", "\u00a0 새  제목 \uFEFF");
            assertEquals("새  제목", result.title());
            assertEquals(set.getId(), result.id());
            assertEquals(sets.findById(set.getId()).orElseThrow().getCreatedAt(), result.createdAt());
            assertEquals("가".repeat(100), service.rename(owner.getId(), set.getId(), "가".repeat(100)).title());
            assertEquals(emoji.repeat(50), service.rename(owner.getId(), set.getId(), emoji.repeat(50)).title());
            String beforeFailure = title(set.getId());
            doAnswer(call -> {
                entityManager.flush();
                throw new org.springframework.dao.DataAccessResourceFailureException("synthetic");
            }).when(sets).saveAndFlush(any(QuizSet.class));
            try {
                assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.rename(owner.getId(), set.getId(), "Rollback"));
            } finally { reset(sets); }
            assertEquals(beforeFailure, title(set.getId()));
            assertEquals(history, jdbc.queryForMap("SELECT * FROM quiz_attempt WHERE id=?", attempt.getId()));
            assertEquals(response, jdbc.queryForMap("SELECT * FROM quiz_answer WHERE id=?", answer.getId()));
            assertEquals("Question", items.findById(item.getId()).orElseThrow().getQuestion());
            assertEquals("hash", sets.findById(set.getId()).orElseThrow().getSourceHash());
            assertEquals(beforeFailure, sets.listForMaterial(material.getId()).get(0).getTitle());
        } finally {
            reset(sets);
            // ! Remove only this test's synthetic material and account.
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id=?", materialId);
            jdbc.update("DELETE FROM app_user WHERE email=?", email);
            assertFalse(members.existsByEmail(email));
        }
    }
    private String title(String id) {
        return jdbc.queryForObject("SELECT title FROM quiz_set WHERE id=?", String.class, id);
    }
    private void assertStatus(int expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(org.springframework.web.server.ResponseStatusException.class, call).getStatusCode().value());
    }
}
