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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class QuizSubmissionMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Autowired com.onrelearn.quiz.service.QuizSubmissionService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean QuizAttemptRepository attempts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean QuizAnswerRepository answers;


    @Autowired com.onrelearn.quiz.service.QuizAnswerService saves;
    @Test void gradesAtomicallyAndSerializesWithSaves() throws Exception {
        String email=UUID.randomUUID()+"@example.invalid";
        String materialId=null;
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        try {
            var owner=members.saveAndFlush(new Member(email,"synthetic"));
            var grade=grades.saveAndFlush(new Grade(owner,1,1,true));
            var subject=subjects.saveAndFlush(new Subject(grade,"Answer"));
            var material=materials.saveAndFlush(new Material(subject,1,"Lecture","users/synthetic/test.pdf"));
            materialId=material.getId();
            var set=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash").purpose(QuizPurpose.GENERAL).build());
            var mcq=items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("mcq").question("Q").choices("[\"A\",\"B\"]").answerKey("{\"correctIndex\":1}").points(2).build());
            var tf=items.saveAndFlush(QuizItem.builder().quizSet(set).position(2).type("tf").question("Q").answerKey("{\"correct\":true}").points(2).build());
            var shortItem=items.saveAndFlush(QuizItem.builder().quizSet(set).position(3).type("short").question("Q").answerKey("{\"accepted\":[\"A\"]}").points(2).build());
            var attempt=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));

            saves.save(owner.getId(),attempt.getId(),mcq.getId(),json.readTree("{\"selectedIndex\":1}"));
            assertStatus(401,()->service.submit(null,attempt.getId()));
            assertStatus(400,()->service.submit(owner.getId()," "));
            assertStatus(403,()->service.submit("other",attempt.getId()));
            assertStatus(403,()->service.submit(owner.getId(),"missing"));
            var original=answers.findByAttempt_IdAndItem_Id(attempt.getId(),mcq.getId()).orElseThrow();
            // ! Fail at the final flush after answers and attempt have been mutated.
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"))
                .when(attempts).saveAndFlush(org.mockito.ArgumentMatchers.any(QuizAttempt.class));
            try { assertThrows(org.springframework.dao.DataAccessException.class,()->service.submit(owner.getId(),attempt.getId())); }
            finally { org.mockito.Mockito.reset(attempts); }
            assertEquals(com.onrelearn.quiz.model.QuizAttemptStatus.IN_PROGRESS,attempts.findById(attempt.getId()).orElseThrow().getStatus());
            assertEquals(1,answers.findByAttempt_Id(attempt.getId()).size());
            assertNull(answers.findById(original.getId()).orElseThrow().getIsCorrect());
            var ready=new java.util.concurrent.CountDownLatch(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> task=()->{
                ready.countDown(); assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));
                try { service.submit(owner.getId(),attempt.getId()); return 200; }
                catch(org.springframework.web.server.ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            var a=pool.submit(task); var b=pool.submit(task);
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)));
            var graded=attempts.findById(attempt.getId()).orElseThrow();
            assertEquals(33,graded.getScore()); assertEquals(100,graded.getMaxScore());
            assertNotNull(graded.getSubmittedAt()); assertEquals(graded.getSubmittedAt(),graded.getGradedAt());
            assertEquals(3,answers.findByAttempt_Id(attempt.getId()).size());
            assertEquals(original.getResponse(),answers.findById(original.getId()).orElseThrow().getResponse());
            assertTrue(answers.findById(original.getId()).orElseThrow().getIsCorrect());
            assertEquals(2,answers.findById(original.getId()).orElseThrow().getEarned());
            var unanswered=answers.findByAttempt_IdAndItem_Id(attempt.getId(),tf.getId()).orElseThrow();
            assertEquals(json.readTree("{}"),json.readTree(unanswered.getResponse()));
            assertFalse(unanswered.getIsCorrect()); assertEquals(0,unanswered.getEarned());
            assertStatus(409,()->saves.save(owner.getId(),attempt.getId(),mcq.getId(),json.readTree("{\"selectedIndex\":0}")));
            // * Save and submit race: either saved response is graded, or the save is rejected.
            var race=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
            var gate=new java.util.concurrent.CountDownLatch(1);
            var submit=pool.submit(()->{ gate.await(); return service.submit(owner.getId(),race.getId()); });
            var save=pool.submit(()->{
                gate.await();
                try { saves.save(owner.getId(),race.getId(),mcq.getId(),json.readTree("{\"selectedIndex\":1}")); return 200; }
                catch(org.springframework.web.server.ResponseStatusException e) { return e.getStatusCode().value(); }
            });
            gate.countDown();
            var result=submit.get(20,java.util.concurrent.TimeUnit.SECONDS);
            int saveStatus=save.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(saveStatus==200 || saveStatus==409);
            assertEquals(saveStatus==200 ? 33 : 0,result.score());
            assertEquals(saveStatus==200,answers.findByAttempt_IdAndItem_Id(race.getId(),mcq.getId()).orElseThrow().getIsCorrect());
            var emptySet=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("empty").purpose(QuizPurpose.GENERAL).build());
            var empty=attempts.saveAndFlush(new QuizAttempt(owner.getId(),emptySet));
            assertEquals(0,service.submit(owner.getId(),empty.getId()).maxScore());
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS));
            // ! Only this test's synthetic material and account are removed.
            if(materialId!=null) jdbc.update("DELETE FROM material WHERE id=?",materialId);
            jdbc.update("DELETE FROM app_user WHERE email=?",email);
            assertFalse(members.existsByEmail(email));
        }
    }
    private void assertStatus(int expected,org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected,assertThrows(org.springframework.web.server.ResponseStatusException.class,call).getStatusCode().value());
    }
}
