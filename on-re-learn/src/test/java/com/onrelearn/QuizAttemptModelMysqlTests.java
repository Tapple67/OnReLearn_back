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
class QuizAttemptModelMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;

    @Test @Transactional
    void preservesAttemptsAnswersAndConstraintsWithoutDeletingHistory() {
        Member owner=members.saveAndFlush(new Member(UUID.randomUUID()+"@example.invalid","synthetic"));
        Grade grade=grades.saveAndFlush(new Grade(owner,1,1,true));
        Subject subject=subjects.saveAndFlush(new Subject(grade,"Attempts"));
        Material material=materials.saveAndFlush(new Material(subject,1,"Lecture","users/synthetic/test.pdf"));
        QuizSet set=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash").purpose(QuizPurpose.GENERAL).build());
        QuizItem item=items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("tf").question("Q").answerKey("{\"correct\":true}").points(100).build());
        QuizAttempt first=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        QuizAttempt second=attempts.saveAndFlush(new QuizAttempt(owner.getId(),set));
        jdbc.update("UPDATE quiz_attempt SET started_at='2020-01-01 00:00:00' WHERE id=?",first.getId());
        QuizAnswer answer=answers.saveAndFlush(new QuizAnswer(first,item,"{\"value\":false,\"text\":\"\"}"));
        entityManager.clear();
        var loaded=attempts.findByIdAndUserId(first.getId(),owner.getId()).orElseThrow();
        assertEquals(com.onrelearn.quiz.model.QuizAttemptStatus.IN_PROGRESS,loaded.getStatus());
        assertNull(loaded.getScore()); assertNull(loaded.getMaxScore()); assertNull(loaded.getSubmittedAt()); assertNull(loaded.getGradedAt());
        assertTrue(attempts.findByIdAndUserId(first.getId(),"other").isEmpty());
        assertEquals(java.util.List.of(second.getId(),first.getId()),attempts.findByUserIdAndQuizSet_IdOrderByStartedAtDesc(owner.getId(),set.getId()).stream().map(QuizAttempt::getId).toList());
        var saved=answers.findByAttempt_IdAndItem_Id(first.getId(),item.getId()).orElseThrow();
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        assertFalse(json.readTree(saved.getResponse()).get("value").booleanValue());
        assertNull(saved.getIsCorrect()); assertNull(saved.getEarned()); assertNull(saved.getFeedback());
        assertFalse(saved.toString().contains("response"));
        var created=saved.getCreatedAt();
        saved.replaceResponse("{\"text\":\""+"x".repeat(5000)+"\",\"index\":0}");
        answers.flush(); entityManager.clear();
        saved=answers.findById(answer.getId()).orElseThrow();
        assertEquals(5000,json.readTree(saved.getResponse()).get("text").stringValue().length());
        assertEquals(created,saved.getCreatedAt());
        assertFalse(saved.getUpdatedAt().isBefore(saved.getCreatedAt()));
        jdbc.update("UPDATE quiz_attempt SET status='GRADED',score=0,max_score=100,submitted_at=NOW(6),graded_at=NOW(6) WHERE id=?",first.getId());
        jdbc.update("UPDATE quiz_answer SET is_correct=false,earned=0,feedback=? WHERE id=?","Feedback",answer.getId());
        entityManager.clear();
        loaded=attempts.findById(first.getId()).orElseThrow();
        assertEquals(com.onrelearn.quiz.model.QuizAttemptStatus.GRADED,loaded.getStatus());
        assertEquals(0,loaded.getScore()); assertEquals(100,loaded.getMaxScore()); assertNotNull(loaded.getSubmittedAt()); assertNotNull(loaded.getGradedAt());
        saved=answers.findById(answer.getId()).orElseThrow();
        assertEquals(false,saved.getIsCorrect()); assertEquals(0,saved.getEarned()); assertEquals("Feedback",saved.getFeedback());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO quiz_answer (id,attempt_id,quiz_item_id,response,created_at,updated_at) VALUES (?,?,?,JSON_OBJECT(),NOW(6),NOW(6))",UUID.randomUUID().toString(),first.getId(),item.getId()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE quiz_answer SET attempt_id='missing' WHERE id=?",answer.getId()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE quiz_answer SET quiz_item_id='missing' WHERE id=?",answer.getId()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE quiz_attempt SET quiz_set_id='missing' WHERE id=?",first.getId()));
        assertCheck(()->jdbc.update("UPDATE quiz_attempt SET status='invalid' WHERE id=?",first.getId()));
        QuizSet other=sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("other").purpose(QuizPurpose.GENERAL).build());
        QuizItem otherItem=items.saveAndFlush(QuizItem.builder().quizSet(other).position(1).type("tf").question("Q").answerKey("{}").points(1).build());
        assertThrows(IllegalArgumentException.class,()->new QuizAnswer(first,otherItem,"{}"));
        // ! Cascade checks touch only this transaction's synthetic rows; all changes roll back.
        jdbc.update("DELETE FROM quiz_attempt WHERE id=?",first.getId());
        entityManager.clear(); assertTrue(answers.findById(answer.getId()).isEmpty());
        assertTrue(attempts.findById(second.getId()).isPresent());
        var secondAnswer=answers.saveAndFlush(new QuizAnswer(attempts.findById(second.getId()).orElseThrow(),items.findById(item.getId()).orElseThrow(),"{}"));
        jdbc.update("DELETE FROM quiz_item WHERE id=?",item.getId());
        entityManager.clear(); assertTrue(answers.findById(secondAnswer.getId()).isEmpty());
        jdbc.update("DELETE FROM quiz_set WHERE id=?",set.getId());
        entityManager.clear(); assertTrue(attempts.findById(second.getId()).isEmpty());
    }
    private static void assertCheck(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(org.springframework.dao.DataAccessException.class, action);
        var cause = error.getMostSpecificCause();
        assertInstanceOf(java.sql.SQLException.class, cause);
        assertEquals(3819, ((java.sql.SQLException) cause).getErrorCode());
    }
}
