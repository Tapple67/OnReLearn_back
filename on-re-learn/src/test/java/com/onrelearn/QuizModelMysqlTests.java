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
class QuizModelMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void preservesQuizSnapshotsJsonAndDatabaseConstraints() {
        // * 시험의 합성 행은 종료 시 모두 롤백한다.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Summary test"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/materials/test.pdf"));
        QuizSet first = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("a".repeat(64))
            .purpose(QuizPurpose.GENERAL).sourceTopics("[\"topic\"]").sourceItemIds("[]").build());
        QuizSet second = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("a".repeat(64))
            .purpose(QuizPurpose.WRONG_REVIEW).summaryId("historical-summary").sourceAttemptId("historical-attempt")
            .provider("synthetic").model("test").promptVersion("quiz.v1").title("Quiz").build());
        QuizItem item = items.saveAndFlush(QuizItem.builder().quizSet(first).position(1).type("mcq")
            .question("Question").choices("[\"A\",\"B\"]").answerKey("{\"correctIndex\":1}")
            .explanation("Explanation").topic("Topic").points(10)
            .evidence("{\"source\":\"pdf\",\"page\":2}").signalHits("[\"important\"]").build());
        items.saveAndFlush(QuizItem.builder().quizSet(first).position(2).type("tf").question("True?")
            .answerKey("{\"correct\":true}").points(20).build());
        items.saveAndFlush(QuizItem.builder().quizSet(second).position(1).type("short").question("Name?")
            .answerKey("{\"accepted\":[\"answer\"]}").points(100).build());
        entityManager.clear();
        assertEquals(2, sets.findByMaterial_IdOrderByCreatedAtDesc(material.getId()).size());
        var loaded = sets.findById(second.getId()).orElseThrow();
        assertEquals("historical-summary", loaded.getSummaryId());
        assertEquals("historical-attempt", loaded.getSourceAttemptId());
        assertEquals(QuizPurpose.WRONG_REVIEW, loaded.getPurpose());
        assertEquals("test", loaded.getModel());
        assertEquals("quiz.v1", loaded.getPromptVersion());
        assertNull(loaded.getSourceTopics());
        assertNotNull(loaded.getCreatedAt());
        var saved = items.findByQuizSet_IdOrderByPositionAsc(first.getId());
        assertEquals(java.util.List.of(1,2), saved.stream().map(QuizItem::getPosition).toList());
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(1, json.readTree(saved.get(0).getAnswerKey()).get("correctIndex").intValue());
        assertEquals("B", json.readTree(saved.get(0).getChoices()).get(1).stringValue());
        assertEquals(2, json.readTree(saved.get(0).getEvidence()).get("page").intValue());
        assertEquals("important", json.readTree(saved.get(0).getSignalHits()).get(0).stringValue());
        assertNull(saved.get(1).getChoices());
        assertEquals(item, saved.get(0));
        assertFalse(saved.get(0).toString().contains("correctIndex"));
        assertFalse(saved.get(0).toString().contains("Question"));
        assertCheck(() -> jdbc.update("UPDATE quiz_item SET points = 0 WHERE id = ?", item.getId()));
        assertCheck(() -> jdbc.update("UPDATE quiz_item SET item_order = 0 WHERE id = ?", item.getId()));
        assertCheck(() -> jdbc.update("UPDATE quiz_item SET type = 'invalid' WHERE id = ?", item.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE quiz_item SET quiz_set_id = 'missing' WHERE id = ?", item.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE quiz_set SET material_id = 'missing' WHERE id = ?", first.getId()));
        assertCheck(() -> jdbc.update("UPDATE quiz_set SET purpose = 'invalid' WHERE id = ?", first.getId()));
        // ! Only synthetic rows from this rollback transaction are deleted to verify cascades.
        jdbc.update("DELETE FROM material WHERE id = ?", material.getId());
        entityManager.clear();
        assertTrue(sets.findById(first.getId()).isEmpty());
        assertTrue(sets.findById(second.getId()).isEmpty());
        assertTrue(items.findById(item.getId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_item WHERE quiz_set_id IN (?, ?)", Integer.class, first.getId(), second.getId()));
    }
    private static void assertCheck(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(org.springframework.dao.DataAccessException.class, action);
        var cause = error.getMostSpecificCause();
        assertInstanceOf(java.sql.SQLException.class, cause);
        assertEquals(3819, ((java.sql.SQLException) cause).getErrorCode());
    }
}
