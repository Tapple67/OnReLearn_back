package com.onrelearn;

import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

@Tag("e2eSeed")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SyntheticGoalProgressSeedTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.onrelearn.quiz.repository.QuizItemRepository items;
    @Autowired com.onrelearn.quiz.repository.QuizAnswerRepository answers;
    @Autowired com.onrelearn.material.repository.MaterialPageTextRepository pages;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void createsOnlyTheNamedSyntheticGoalProgressAccount() {
        String email = System.getProperty("onrelearn.e2e.seed.email", "");
        assertTrue(email.matches("e2e-[0-9a-f-]{36}@example\\.invalid"),
            "Only an explicitly named synthetic E2E account can be seeded");
        assertFalse(members.existsByEmail(email), "Synthetic account must be new");
        String ownerId = null;
        boolean complete = false;
        try {
            registration.register(email, "synthetic-password");
            ownerId = members.findByEmail(email).orElseThrow().getId();
            var grade = grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0);
            var subject = subjects.saveAndFlush(new Subject(grade, "Synthetic progress"));
            boolean recordPdf = Boolean.getBoolean("onrelearn.e2e.seed.record-pdf");
            var material = materials.saveAndFlush(recordPdf
                ? Material.forPdfUpload(subject, 1, "Synthetic progress", ownerId)
                : new Material(subject, 1, "Synthetic progress", "users/synthetic/progress.pdf"));
            if (recordPdf) {
                // * Only synthetic metadata is seeded; the local browser fixture supplies PDF bytes.
                pages.saveAndFlush(new com.onrelearn.material.entity.MaterialPageText(material, 1,
                    "Synthetic page 1", "synthetic"));
                pages.saveAndFlush(new com.onrelearn.material.entity.MaterialPageText(material, 2,
                    "Synthetic page 2", "synthetic"));
            }
            var set = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("synthetic-progress")
                .purpose(QuizPurpose.GENERAL).build());
            Instant now = Instant.now();
            var today = attempts.saveAndFlush(new QuizAttempt(ownerId, set));
            today.completeGrading(0, 100, now);
            attempts.saveAndFlush(today);
            var old = attempts.saveAndFlush(new QuizAttempt(ownerId, set));
            old.completeGrading(0, 100, now.minus(8, ChronoUnit.DAYS));
            attempts.saveAndFlush(old);
            if (Boolean.getBoolean("onrelearn.e2e.seed.weak-topic")) {
                // * Two wrong answers on one topic enable the real statistics-to-review browser flow.
                pages.saveAndFlush(new com.onrelearn.material.entity.MaterialPageText(material, 1,
                    "분수는 전체를 같은 크기로 나눈 부분을 나타낸다.", "synthetic"));
                var item = items.saveAndFlush(com.onrelearn.quiz.entity.QuizItem.builder()
                    .quizSet(set).position(1).type("tf").question("분수는 부분을 나타낸다.")
                    .answerKey("{\"correct\":true}").points(100).topic("분수")
                    .explanation("분수는 전체에 대한 부분이다.").build());
                for (var completed : java.util.List.of(today, old)) {
                    var answer = new com.onrelearn.quiz.entity.QuizAnswer(completed, item, "{\"value\":false}");
                    answer.recordGrade(false, 0);
                    answers.saveAndFlush(answer);
                }
                assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer WHERE quiz_item_id=?", Long.class, item.getId()));
            }
            attempts.saveAndFlush(new QuizAttempt(ownerId, set));
            assertEquals(3, attempts.findByUserIdAndQuizSet_IdOrderByStartedAtDesc(ownerId, set.getId()).size());
            complete = true;
            // ! Commit only the checked synthetic seed; failures roll the entire seed back.
            org.springframework.test.context.transaction.TestTransaction.flagForCommit();
            org.springframework.test.context.transaction.TestTransaction.end();
        } finally {
            // ! Failed seeding must leave no partially committed fixture.
            if (!complete) org.springframework.test.context.transaction.TestTransaction.flagForRollback();
        }
    }
}
