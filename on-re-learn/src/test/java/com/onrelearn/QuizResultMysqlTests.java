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
class QuizResultMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Autowired com.onrelearn.quiz.service.QuizResultService service;
    @Autowired QuizAttemptRepository attempts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean QuizAnswerRepository answers;


    @Test void readsOnlyOwnedCompletedResultsAndPreservesNulls() throws Exception {
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

            assertStatus(401,()->service.get(null,attempt.getId()));
            assertStatus(400,()->service.get(owner.getId()," "));
            assertStatus(404,()->service.get("other",attempt.getId()));
            assertStatus(404,()->service.get(owner.getId(),"missing"));
            assertStatus(400,()->service.get(owner.getId(),attempt.getId()));
            var answer=answers.saveAndFlush(new QuizAnswer(attempt,tf,"{\"value\":false}"));
            jdbc.update("UPDATE quiz_attempt SET status='SUBMITTED' WHERE id=?",attempt.getId());
            var submitted=service.get(owner.getId(),attempt.getId());
            assertEquals("SUBMITTED",submitted.status()); assertNull(submitted.score());
            assertNull(submitted.gradedAt()); assertNull(submitted.answers().get(0).isCorrect());
            assertEquals(materialId,submitted.quizSet().materialId());
            assertEquals(java.util.List.of(1,2,3),submitted.quizSet().items().stream().map(i->i.order()).toList());
            assertEquals(1,submitted.quizSet().items().get(0).answerKey().get("correctIndex").intValue());
            assertEquals("B",submitted.quizSet().items().get(0).choices().get(1).stringValue());
            assertNull(submitted.quizSet().items().get(1).choices());
            assertFalse(submitted.answers().get(0).response().get("value").booleanValue());
            jdbc.update("UPDATE quiz_attempt SET status='GRADED',score=0,max_score=100,submitted_at=NOW(6),graded_at=NOW(6) WHERE id=?",attempt.getId());
            jdbc.update("UPDATE quiz_answer SET is_correct=FALSE,earned=0,feedback='Review' WHERE id=?",answer.getId());
            var graded=service.get(owner.getId(),attempt.getId());
            assertEquals(0,graded.score()); assertEquals(100,graded.maxScore()); assertNotNull(graded.gradedAt());
            assertEquals(0,graded.answers().get(0).earned()); assertEquals("Review",graded.answers().get(0).feedback());
            var serialized=json.readTree(json.writeValueAsString(graded));
            assertEquals(9,serialized.size()); assertFalse(serialized.has("userId"));
            assertEquals(9,serialized.get("quizSet").get("items").get(0).size());
            assertEquals(5,serialized.get("answers").get(0).size());
            assertFalse(serialized.get("quizSet").has("sourceHash"));
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"))
                .when(answers).findByAttempt_Id(attempt.getId());
            try { assertThrows(org.springframework.dao.DataAccessException.class,()->service.get(owner.getId(),attempt.getId())); }
            finally { org.mockito.Mockito.reset(answers); }
            assertEquals(1,answers.findByAttempt_Id(attempt.getId()).size());
            assertEquals(graded.gradedAt(),service.get(owner.getId(),attempt.getId()).gradedAt());
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
