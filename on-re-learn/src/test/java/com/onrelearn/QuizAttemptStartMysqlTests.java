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
class QuizAttemptStartMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Autowired com.onrelearn.quiz.service.QuizAttemptStartService service;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;

    @Test void startsConcurrentlyRestoresAndPreservesHistory() throws Exception {
        String email=UUID.randomUUID()+"@example.invalid";
        String materialId=null;
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            Member owner=members.saveAndFlush(new Member(email,"synthetic"));
            Grade grade=grades.saveAndFlush(new Grade(owner,1,1,true));
            Subject subject=subjects.saveAndFlush(new Subject(grade,"Start"));
            Material material=materials.saveAndFlush(new Material(subject,1,"Lecture","users/synthetic/test.pdf"));
            materialId=material.getId();
            QuizSet set=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash").purpose(QuizPurpose.GENERAL).build());
            var secondItem=items.saveAndFlush(QuizItem.builder().quizSet(set).position(2).type("tf").question("Second").answerKey("{\"correct\":true}").explanation("Private explanation").points(50).build());
            var firstItem=items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("mcq").question("First").choices("[\"A\",\"B\"]").answerKey("{\"correctIndex\":1}").points(50).build());
            assertStatus(401,()->service.start(null,set.getId()));
            assertStatus(400,()->service.start(owner.getId()," "));
            assertStatus(403,()->service.start("other",set.getId()));
            assertStatus(403,()->service.start(owner.getId(),"missing"));
            var ready=new java.util.concurrent.CountDownLatch(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<com.onrelearn.quiz.service.QuizAttemptStartService.Started> task=()->{
                ready.countDown(); assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));
                return service.start(owner.getId(),set.getId());
            };
            var a=pool.submit(task); var b=pool.submit(task);
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            var first=a.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var second=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.attemptId(),second.attemptId());
            assertEquals(1,attempts.findByUserIdAndQuizSet_IdOrderByStartedAtDesc(owner.getId(),set.getId()).size());
            assertEquals("success",first.status());
            assertEquals(java.util.List.of(1,2),first.quizSet().items().stream().map(i->i.order()).toList());
            assertEquals("B",first.quizSet().items().get(0).choices().get(1).stringValue());
            assertNull(first.quizSet().items().get(1).choices());
            assertTrue(first.existingAnswers().isEmpty());
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            String serialized=json.writeValueAsString(first);
            assertFalse(serialized.contains("answerKey")); assertFalse(serialized.contains("explanation"));
            var attempt=attempts.findById(first.attemptId()).orElseThrow();
            answers.saveAndFlush(new QuizAnswer(attempt,secondItem,"{\"value\":false,\"text\":\"\"}"));
            var restored=service.start(owner.getId(),set.getId());
            assertEquals(first.attemptId(),restored.attemptId());
            assertEquals(secondItem.getId(),restored.existingAnswers().get(0).quizItemId());
            assertFalse(restored.existingAnswers().get(0).response().get("value").booleanValue());
            assertEquals("",restored.existingAnswers().get(0).response().get("text").stringValue());
            // * An unrelated user's synthetic attempt must never be restored.
            var foreign=attempts.saveAndFlush(new QuizAttempt("other",set));
            assertEquals(first.attemptId(),service.start(owner.getId(),set.getId()).attemptId());
            var completed=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
            jdbc.update("UPDATE quiz_attempt SET status='SUBMITTED' WHERE id=?",completed.getId());
            var done=service.start(owner.getId(),set.getId());
            assertEquals("already-submitted",done.status()); assertEquals(completed.getId(),done.attemptId());
            assertNull(done.quizSet()); assertTrue(done.existingAnswers().isEmpty());
            assertTrue(attempts.existsById(first.attemptId())); assertTrue(attempts.existsById(foreign.getId()));
            assertEquals(1,answers.findByAttempt_Id(first.attemptId()).size());
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS));
            // ! Only this test's committed synthetic material/account are removed.
            if(materialId!=null) jdbc.update("DELETE FROM material WHERE id=?",materialId);
            jdbc.update("DELETE FROM app_user WHERE email=?",email);
            assertFalse(members.existsByEmail(email));
        }
    }
    private void assertStatus(int expected,org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected,assertThrows(org.springframework.web.server.ResponseStatusException.class,call).getStatusCode().value());
    }
}
