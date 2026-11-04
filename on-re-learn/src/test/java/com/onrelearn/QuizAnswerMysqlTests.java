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
class QuizAnswerMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Autowired com.onrelearn.quiz.service.QuizAnswerService service;
    @Autowired QuizAttemptRepository attempts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean QuizAnswerRepository answers;


    @Test void validatesUpsertsAndPreservesAnswers() throws Exception {
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
            var mcq=items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("mcq").question("Q").choices("[\"A\",\"B\"]").answerKey("{\"correctIndex\":1}").points(40).build());
            var tf=items.saveAndFlush(QuizItem.builder().quizSet(set).position(2).type("tf").question("Q").answerKey("{\"correct\":true}").points(30).build());
            var shortItem=items.saveAndFlush(QuizItem.builder().quizSet(set).position(3).type("short").question("Q").answerKey("{\"accepted\":[\"A\"]}").points(30).build());
            var attempt=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
            var response=json.readTree("{\"selectedIndex\":0}");
            assertStatus(401,()->service.save(null,attempt.getId(),mcq.getId(),response));
            assertStatus(400,()->service.save(owner.getId()," ",mcq.getId(),response));
            assertStatus(403,()->service.save("other",attempt.getId(),mcq.getId(),response));
            assertStatus(403,()->service.save(owner.getId(),"missing",mcq.getId(),response));
            assertStatus(400,()->service.save(owner.getId(),attempt.getId(),"missing",response));
            var foreignSet=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("other").purpose(QuizPurpose.GENERAL).build());
            var foreignItem=items.saveAndFlush(QuizItem.builder().quizSet(foreignSet).position(1).type("tf").question("Q").answerKey("{\"correct\":true}").points(1).build());
            assertStatus(400,()->service.save(owner.getId(),attempt.getId(),foreignItem.getId(),json.readTree("{\"value\":false}")));
            var ready=new java.util.concurrent.CountDownLatch(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Void> task=()->{
                ready.countDown(); assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));
                service.save(owner.getId(),attempt.getId(),mcq.getId(),response); return null;
            };
            var a=pool.submit(task); var b=pool.submit(task);
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            a.get(20,java.util.concurrent.TimeUnit.SECONDS); b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var original=answers.findByAttempt_IdAndItem_Id(attempt.getId(),mcq.getId()).orElseThrow();
            assertEquals(1,answers.findByAttempt_Id(attempt.getId()).size());
            assertEquals(0,json.readTree(original.getResponse()).get("selectedIndex").intValue());
            for(String invalid:java.util.List.of("null","false","[]","{}","{\"selectedIndex\":-1}","{\"selectedIndex\":2}","{\"selectedIndex\":0.5}","{\"selectedIndex\":\"0\"}","{\"selectedIndex\":999999999999}"))
                assertStatus(400,()->service.save(owner.getId(),attempt.getId(),mcq.getId(),json.readTree(invalid)));
            assertEquals(json.readTree(original.getResponse()),json.readTree(answers.findById(original.getId()).orElseThrow().getResponse()));
            service.save(owner.getId(),attempt.getId(),mcq.getId(),json.readTree("{\"selectedIndex\":null}"));
            var changed=answers.findById(original.getId()).orElseThrow();
            assertEquals(original.getCreatedAt(),changed.getCreatedAt());
            assertFalse(changed.getUpdatedAt().isBefore(original.getUpdatedAt()));
            assertTrue(json.readTree(changed.getResponse()).get("selectedIndex").isNull());
            service.save(owner.getId(),attempt.getId(),tf.getId(),json.readTree("{\"value\":false}"));
            assertFalse(json.readTree(answers.findByAttempt_IdAndItem_Id(attempt.getId(),tf.getId()).orElseThrow().getResponse()).get("value").booleanValue());
            assertStatus(400,()->service.save(owner.getId(),attempt.getId(),tf.getId(),json.readTree("{\"value\":\"false\"}")));
            service.save(owner.getId(),attempt.getId(),tf.getId(),json.readTree("{\"value\":null}"));
            for(String text:java.util.List.of("","  Original \n text  ","x".repeat(10000))) {
                service.save(owner.getId(),attempt.getId(),shortItem.getId(),json.createObjectNode().put("text",text));
                assertEquals(text,json.readTree(answers.findByAttempt_IdAndItem_Id(attempt.getId(),shortItem.getId()).orElseThrow().getResponse()).get("text").stringValue());
            }
            assertStatus(400,()->service.save(owner.getId(),attempt.getId(),shortItem.getId(),json.readTree("{\"text\":null}")));
            // ! A repository failure after mutating a managed answer must roll back the original.
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"))
                    .when(answers).saveAndFlush(org.mockito.ArgumentMatchers.any(QuizAnswer.class));
            try {
                assertThrows(org.springframework.dao.DataAccessException.class,
                        ()->service.save(owner.getId(),attempt.getId(),mcq.getId(),response));
            } finally { org.mockito.Mockito.reset(answers); }
            assertTrue(json.readTree(answers.findById(original.getId()).orElseThrow().getResponse()).get("selectedIndex").isNull());
            for(String status:java.util.List.of("SUBMITTED","GRADED")) {
                jdbc.update("UPDATE quiz_attempt SET status=? WHERE id=?",status,attempt.getId());
                assertStatus(409,()->service.save(owner.getId(),attempt.getId(),mcq.getId(),response));
                assertTrue(json.readTree(answers.findById(original.getId()).orElseThrow().getResponse()).get("selectedIndex").isNull());
            }
            assertEquals(3,answers.findByAttempt_Id(attempt.getId()).size());
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
