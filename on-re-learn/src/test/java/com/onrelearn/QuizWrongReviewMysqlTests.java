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
class QuizWrongReviewMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired com.onrelearn.quiz.service.QuizWrongReviewService service;

    @Test void onlyOwnedCompletedIncorrectAnswersBecomeReviewInput() {
        var owner=members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid","synthetic"));
        var grade=grades.saveAndFlush(new Grade(owner,1,1,true));
        var subject=subjects.saveAndFlush(new Subject(grade,"Review"));
        var material=materials.saveAndFlush(new Material(subject,1,"Lecture","users/synthetic/review.pdf"));
        var set=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash").purpose(QuizPurpose.GENERAL).build());
        var attempt=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        status(401,()->service.load(null,material.getId(),attempt.getId()));
        status(400,()->service.load(owner.getId()," ",attempt.getId()));
        status(404,()->service.load("other",material.getId(),attempt.getId()));
        status(404,()->service.load(owner.getId(),material.getId(),"missing"));
        status(404,()->service.load(owner.getId(),material.getId(),attempt.getId()));
        var wrong=items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("tf").question("Wrong")
                .answerKey("{\"correct\":true}").points(1).topic("  ").explanation("Explain").evidence("{\"page\":\"2\"}").build());
        var correct=items.saveAndFlush(QuizItem.builder().quizSet(set).position(2).type("tf").question("Correct")
                .answerKey("{\"correct\":true}").points(1).build());
        var ungraded=items.saveAndFlush(QuizItem.builder().quizSet(set).position(3).type("tf").question("Ungraded")
                .answerKey("{\"correct\":true}").points(1).build());
        var a=new QuizAnswer(attempt,wrong,"{\"value\":false}"); a.recordGrade(false,0); answers.saveAndFlush(a);
        var b=new QuizAnswer(attempt,correct,"{\"value\":true}"); b.recordGrade(true,1); answers.saveAndFlush(b);
        answers.saveAndFlush(new QuizAnswer(attempt,ungraded,"{}"));
        attempt.completeGrading(33,100,java.time.Instant.now()); attempts.saveAndFlush(attempt);
        em.clear();
        status(404,()->service.load(owner.getId(),"other-material",attempt.getId()));
        var result=service.load(owner.getId(),material.getId(),attempt.getId());
        assertEquals(java.util.List.of(wrong.getId()),result.sourceItemIds());
        assertEquals(java.util.List.of("General concept"),result.focus().topics());
        assertEquals(1,result.focus().wrongItems().size());
        assertEquals("Wrong",result.focus().wrongItems().get(0).question());
        assertEquals("Explain",result.focus().wrongItems().get(0).explanation());
        assertEquals("2",result.focus().wrongItems().get(0).evidence().get("page").stringValue());
        assertEquals(3,answers.findByAttempt_Id(attempt.getId()).size());
        assertThrows(UnsupportedOperationException.class,()->result.sourceItemIds().clear());
        var empty=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        empty.completeGrading(0,0,java.time.Instant.now()); attempts.saveAndFlush(empty);
        status(400,()->service.load(owner.getId(),material.getId(),empty.getId()));
        // ! Roll back only the synthetic rows created in this test transaction.
    }
    private void status(int expected,org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected,assertThrows(org.springframework.web.server.ResponseStatusException.class,call).getStatusCode().value());
    }
}
