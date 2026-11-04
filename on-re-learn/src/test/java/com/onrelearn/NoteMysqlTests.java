package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.note.entity.Note;
import com.onrelearn.note.repository.NoteRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class NoteMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired NoteRepository notes;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void notePreservesContentAndSignalsAndEnforcesPageConstraints() {
        // * 합성 행은 실제 onrelearn MySQL 트랜잭션에서만 만들고 시험 종료 시 롤백한다.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "PDF pages"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/materials/test.pdf"));
        Note note = notes.saveAndFlush(Note.builder().material(material).page(1).content("첫 노트\n ").build());
        String noteId = note.getId();
        assertThrows(IllegalArgumentException.class, () -> new Note(material, 0, ""));
        assertThrows(NullPointerException.class, () -> note.replaceContent(null));
        jdbc.update("UPDATE note SET signals = ?, signals_ver = ? WHERE id = ?", "{\"topics\":[\"Java\"]}", "v1", noteId);
        entityManager.clear();
        Note loaded = notes.findByMaterial_IdAndPage(material.getId(), 1).orElseThrow();
        assertEquals("첫 노트\n ", loaded.getContent());
        assertTrue(loaded.getSignals().contains("Java"));
        assertEquals("v1", loaded.getSignalsVer());
        var createdAt = loaded.getCreatedAt();
        loaded.replaceContent("");
        notes.flush();
        entityManager.clear();
        loaded = notes.findById(noteId).orElseThrow();
        assertEquals("", loaded.getContent());
        assertEquals(createdAt, loaded.getCreatedAt());
        assertFalse(loaded.getUpdatedAt().isBefore(createdAt));
        assertTrue(loaded.getSignals().contains("Java"));
        assertEquals(note, loaded);
        assertEquals(note.hashCode(), loaded.hashCode());
        assertFalse(loaded.toString().contains("Java"));

        // ! JDBC 직접 쓰기에도 페이지 unique, 자료 FK, 양수 CHECK를 적용한다.
        String insert = "INSERT INTO note (id, material_id, page, content, created_at, updated_at) VALUES (?, ?, ?, '', NOW(6), NOW(6))";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID().toString(), material.getId(), 1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, UUID.randomUUID().toString(), material.getId(), 0));
        // * 이 시험에서 만든 자료만 삭제하며 전체 합성 변경은 종료 시 롤백한다.
        jdbc.update("DELETE FROM material WHERE id = ?", material.getId());
        entityManager.clear();
        assertTrue(notes.findById(noteId).isEmpty());
    }
}
