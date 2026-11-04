package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.profile.MyPageQuizService;
import com.onrelearn.quiz.entity.QuizAnswer;
import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.entity.QuizItem;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.QuizAnswerRepository;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.quiz.repository.QuizItemRepository;
import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class MyPageQuizServiceMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;
    @Autowired MyPageQuizService profile;

    @Test void ownerSnapshotExcludesForeignAndInProgressRecords() {
        var owner = members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid", "synthetic"));
        var other = members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid", "synthetic"));
        var grade = grades.saveAndFlush(new Grade(owner, 2, 1, true));
        var subject = subjects.saveAndFlush(new Subject(grade, "수학"));
        subjects.saveAndFlush(new Subject(grade, "빈 과목"));
        var material = materials.saveAndFlush(new Material(subject, 1, "자료", "users/synthetic/profile.pdf"));
        var set = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("synthetic")
            .title("퀴즈").purpose(QuizPurpose.GENERAL).build());
        var item = items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("tf")
            .question("Q").answerKey("true").points(100).build());
        var done = attempts.saveAndFlush(new QuizAttempt(owner.getId(), set));
        done.completeGrading(80, 100, Instant.parse("2026-10-19T02:00:00Z"));
        attempts.saveAndFlush(done);
        var answer = answers.saveAndFlush(new QuizAnswer(done, item, "true"));
        answer.recordGrade(true, 100);
        answers.saveAndFlush(answer);
        attempts.saveAndFlush(new QuizAttempt(owner.getId(), set));
        var foreign = attempts.saveAndFlush(new QuizAttempt(other.getId(), set));
        foreign.completeGrading(100, 100, Instant.parse("2026-10-19T02:00:00Z"));
        attempts.saveAndFlush(foreign);
        var foreignAnswer = answers.saveAndFlush(new QuizAnswer(foreign, item, "false"));
        foreignAnswer.recordGrade(false, 0);
        answers.saveAndFlush(foreignAnswer);

        var result = profile.get(owner.getId(), Instant.parse("2026-10-19T03:00:00Z"));
        assertEquals(1, result.totalQuizCount());
        assertEquals(100, result.accuracy());
        assertEquals(2, result.subjectStats().size());
        assertTrue(result.subjectStats().stream().anyMatch(s -> s.name().equals("수학") && s.rate() == 80));
        assertTrue(result.subjectStats().stream().anyMatch(s -> s.name().equals("빈 과목") && s.rate() == 0));
        assertEquals(80, result.chartData().get(6).rate());
        assertEquals(1, result.recentItems().size());
        assertEquals("수학 - 퀴즈", result.recentItems().get(0).title());
        var otherView = profile.get(other.getId(), Instant.parse("2026-10-19T03:00:00Z"));
        assertEquals(0, otherView.totalQuizCount());
        assertEquals(0, otherView.accuracy());
        assertTrue(otherView.subjectStats().isEmpty());
        assertEquals(401, assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> profile.get(" ", Instant.now())).getStatusCode().value());
        // ! The synthetic rows are rolled back with the enclosing test transaction.
    }
}
