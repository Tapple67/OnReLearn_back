package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.summary.entity.MaterialSummary;
import com.onrelearn.summary.repository.MaterialSummaryRepository;
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
class MaterialSummaryMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired MaterialSummaryRepository summaries;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void preservesVariantsAndMetadataAndEnforcesMaterialConstraints() {
        // * 시험의 합성 행은 종료 시 모두 롤백한다.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Summary test"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/materials/test.pdf"));
        MaterialSummary summary = summaries.saveAndFlush(MaterialSummary.builder()
                .material(material).sourceHash("a".repeat(64)).content("legacy-content").build());
        String id = summary.getId();
        entityManager.clear();
        MaterialSummary loaded = summaries.findByMaterial_Id(material.getId()).orElseThrow();
        assertEquals("legacy-content", loaded.getContent());
        assertNull(loaded.getCanonical());
        assertNull(loaded.getAdaptive());
        assertNull(loaded.getSignalsDigest());
        var createdAt = loaded.getCreatedAt();
        String digest = "[{\"page\":1,\"signals\":{\"confuseWith\":[\"" + "topic".repeat(100) + "\"]}}]";
        loaded.replace("b".repeat(64), "canonical-content", "canonical-content", "adaptive-content",
                "both", digest, "synthetic-provider", "synthetic-model", "summary.v6");
        var updatedAt = loaded.getUpdatedAt();
        assertThrows(IllegalArgumentException.class, () -> loaded.replace("", "bad", null, null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> loaded.replace("c".repeat(64), null, null, null, null, null, null, null, null));
        assertEquals(updatedAt, loaded.getUpdatedAt());
        summaries.flush();
        entityManager.clear();
        MaterialSummary reread = summaries.findById(id).orElseThrow();
        assertEquals("b".repeat(64), reread.getSourceHash());
        assertEquals("canonical-content", reread.getContent());
        assertEquals("canonical-content", reread.getCanonical());
        assertEquals("adaptive-content", reread.getAdaptive());
        assertEquals(digest, reread.getSignalsDigest());
        assertEquals("both", reread.getSummaryType());
        assertEquals("synthetic-provider", reread.getProvider());
        assertEquals("synthetic-model", reread.getModel());
        assertEquals("summary.v6", reread.getPromptVersion());
        assertEquals(createdAt, reread.getCreatedAt());
        assertFalse(reread.getUpdatedAt().isBefore(createdAt));
        assertEquals(summary, reread);
        assertEquals(summary.hashCode(), reread.hashCode());
        assertFalse(reread.toString().contains("canonical-content"));
        assertFalse(reread.toString().contains("adaptive-content"));
        assertFalse(reread.toString().contains("topic"));
        String insert = "INSERT INTO material_summary (id, material_id, source_hash, content, created_at, updated_at) VALUES (?, ?, ?, '', NOW(6), NOW(6))";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID().toString(), material.getId(), "a".repeat(64)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64)));
        // ! cascade는 이번 시험에서 생성한 자료에만 확인한다.
        jdbc.update("DELETE FROM material WHERE id = ?", material.getId());
        entityManager.clear();
        assertTrue(summaries.findById(id).isEmpty());
    }
}
