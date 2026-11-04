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
import com.onrelearn.stats.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class MaterialStatsMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;
    @Autowired MaterialStatsService service;
    @Autowired MaterialStatsRepository statistics;
    @Autowired jakarta.persistence.EntityManager em;

    @Test void loadsOnlyOwnedCompletedStatisticsAndPreservesRowsOnFailure() {
        var owner=members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid","synthetic"));
        var other=members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid","synthetic"));
        var grade=grades.saveAndFlush(new Grade(owner,2,1,true));
        var subject=subjects.saveAndFlush(new Subject(grade,"Statistics"));
        var material=materials.saveAndFlush(new Material(subject,3,"Lecture","users/synthetic/stats.pdf"));
        status(401,()->service.get(null,material.getId()));
        status(400,()->service.get(owner.getId()," "));
        status(403,()->service.get(other.getId(),material.getId()));
        status(403,()->service.get(owner.getId(),"missing"));
        assertEquals("NONE",service.get(owner.getId(),material.getId()).understanding().level());

        var set=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash")
            .purpose(QuizPurpose.WRONG_REVIEW).build());
        var item=items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("tf").question("Question")
            .answerKey("{\"correct\":true}").points(1).topic(" Topic ").build());
        var graded=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        var answer=new QuizAnswer(graded,item,"{\"value\":false}");
        answer.recordGrade(false,0); answers.saveAndFlush(answer);
        graded.completeGrading(25,100,Instant.parse("2026-10-12T00:00:00Z")); attempts.saveAndFlush(graded);
        var submitted=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        answers.saveAndFlush(new QuizAnswer(submitted,item,"{}"));
        em.createNativeQuery("update quiz_attempt set status='SUBMITTED' where id=:id")
            .setParameter("id",submitted.getId()).executeUpdate();
        var open=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        answers.saveAndFlush(new QuizAnswer(open,item,"{}"));
        var outsider=attempts.saveAndFlush(new QuizAttempt(other.getId(),set));
        outsider.completeGrading(100,100,Instant.now()); attempts.saveAndFlush(outsider);
        var otherMaterial=materials.saveAndFlush(new Material(subject,4,"Other","users/synthetic/stats-other.pdf"));
        var otherSet=sets.saveAndFlush(QuizSet.builder().material(otherMaterial).sourceHash("hash")
            .purpose(QuizPurpose.GENERAL).build());
        var unrelated=attempts.saveAndFlush(new QuizAttempt(owner.getId(),otherSet));
        unrelated.completeGrading(100,100,Instant.now()); attempts.saveAndFlush(unrelated);
        var empty=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        empty.completeGrading(0,0,Instant.parse("2026-10-11T00:00:00Z")); attempts.saveAndFlush(empty);
        em.clear();

        var result=service.get(owner.getId()," "+material.getId()+" ");
        assertEquals(new MaterialStats.Summary(3,2,0,2,0,8.3,25,25),result.summary());
        assertEquals(3,result.purposeStats().get("WRONG_REVIEW").attempts());
        assertEquals(2,result.typeStats().get("tf").wrong());
        assertEquals("Topic",result.weakTopics().get(0).topic());
        assertEquals("Statistics",result.material().subjectName());
        assertEquals(new MaterialStats.Grade(2,1),result.material().grade());
        assertEquals(3,result.material().week());
        assertEquals(submitted.getId(),result.trend().get(0).attemptId());
        assertTrue(statistics.completedRows(other.getId(),material.getId(),
            java.util.List.of(com.onrelearn.quiz.model.QuizAttemptStatus.GRADED)).isEmpty());

        var failing=(MaterialStatsRepository)java.lang.reflect.Proxy.newProxyInstance(
            MaterialStatsRepository.class.getClassLoader(),new Class<?>[]{MaterialStatsRepository.class},
            (proxy,method,args)->{ throw new org.springframework.dao.DataAccessResourceFailureException("synthetic"); });
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
            ()->new MaterialStatsService(materials,failing).get(owner.getId(),material.getId()));
        assertEquals(result,service.get(owner.getId(),material.getId()));
        assertEquals("{\"value\": false}",answers.findById(answer.getId()).orElseThrow().getResponse());
        assertNull(answers.findByAttempt_Id(submitted.getId()).get(0).getIsCorrect());
        assertEquals(4,attempts.findByUserIdAndQuizSet_IdOrderByStartedAtDesc(owner.getId(),set.getId()).size());
        // ! The enclosing transaction rolls back only this test's synthetic rows.
    }
    private void status(int expected,org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected,assertThrows(org.springframework.web.server.ResponseStatusException.class,call).getStatusCode().value());
    }
}
