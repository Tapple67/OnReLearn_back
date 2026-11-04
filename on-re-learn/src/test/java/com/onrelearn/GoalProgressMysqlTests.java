package com.onrelearn;

import com.onrelearn.goal.GoalProgressService;
import com.onrelearn.goal.entity.Goal;
import com.onrelearn.goal.repository.GoalRepository;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
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
class GoalProgressMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizAttemptRepository attempts;
    @Autowired GoalRepository goals;
    @Autowired GoalProgressService progress;
    @Autowired jakarta.persistence.EntityManager em;

    @Test void onlyOwnersCompletedAttemptsContributeAndZeroGoalStaysZero() {
        var owner = members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid","synthetic"));
        var other = members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid","synthetic"));
        var grade = grades.saveAndFlush(new Grade(owner,2,1,true));
        var subject = subjects.saveAndFlush(new Subject(grade,"Progress"));
        var material = materials.saveAndFlush(new Material(subject,1,"Notes","users/synthetic/progress.pdf"));
        var set = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("synthetic")
            .purpose(QuizPurpose.GENERAL).build());
        var goal = goals.saveAndFlush(new Goal(owner));
        goal.update(0,2,3,15);
        goals.flush();

        var graded = attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        graded.completeGrading(0,100,Instant.parse("2026-10-15T00:30:00Z"));
        attempts.saveAndFlush(graded);
        var submitted = attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        em.createNativeQuery("update quiz_attempt set status='SUBMITTED', submitted_at='2026-10-14 14:30:00' where id=:id")
            .setParameter("id",submitted.getId()).executeUpdate();
        attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        var outsider = attempts.saveAndFlush(new QuizAttempt(other.getId(),set));
        outsider.completeGrading(100,100,Instant.parse("2026-10-15T00:30:00Z"));
        attempts.saveAndFlush(outsider);
        em.clear();

        var result = progress.getQuiz(owner.getId(),Instant.parse("2026-10-15T01:00:00Z"));
        assertEquals(1,result.todayCount());
        assertEquals(2,result.weeklyCount());
        assertEquals(0,result.todayPercent());
        assertEquals(100,result.weeklyPercent());
        assertEquals(10,progress.getQuiz(other.getId(),Instant.parse("2026-10-15T01:00:00Z")).todayPercent());
        // ! Only synthetic rows are created; the enclosing transaction rolls them back.
    }
}
