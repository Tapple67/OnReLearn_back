package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.entity.MaterialPageText;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.MaterialPageTextRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.note.entity.Note;
import com.onrelearn.note.repository.NoteRepository;
import com.onrelearn.summary.service.SummaryInputService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SummaryInputMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired MaterialPageTextRepository pages;
    @Autowired NoteRepository notes;
    @Autowired SummaryInputService service;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void readsOwnedSnapshotWithNotesAndTextWithoutChangingStoredContent() {
        // * Synthetic changes roll back; no storage, queue worker or provider is invoked.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Member other = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Summary input"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/materials/test.pdf"));
        Material empty = materials.saveAndFlush(new Material(subject, 2, "Empty", "users/synthetic/materials/empty.pdf"));
        pages.saveAndFlush(new MaterialPageText(material, 3, " Third ", "PDFBOX"));
        pages.saveAndFlush(new MaterialPageText(material, 1, " First ", "PDFBOX"));
        Note note = notes.saveAndFlush(new Note(material, 2, " Note only "));
        notes.saveAndFlush(new Note(material, 3, " Paired "));
        jdbc.update("UPDATE note SET signals = ? WHERE id = ?", "{\"difficulty\":\"high\"}", note.getId());
        entityManager.clear();
        var input = service.build(owner.getId(), " " + material.getId() + " ", "summary.v6");
        assertEquals(material.getId(), input.materialId());
        assertEquals(subject.getId(), input.subjectId());
        assertEquals("Lecture", input.title());
        assertEquals(1, input.week());
        assertEquals("summary.v6", input.promptVersion());
        assertEquals(List.of(1, 2, 3), input.pages().stream().map(page -> page.page()).toList());
        assertEquals("First", input.pages().get(0).pdfText());
        assertEquals("", input.pages().get(0).note());
        assertNull(input.pages().get(0).noteSignals());
        assertEquals("", input.pages().get(1).pdfText());
        assertEquals("Note only", input.pages().get(1).note());
        assertEquals("high", input.pages().get(1).noteSignals().get("difficulty").asString());
        assertNotNull(input.pages().get(1).noteUpdatedAt());
        assertEquals("Paired", input.pages().get(2).note());
        assertEquals("Third", input.pages().get(2).pdfText());
        assertEquals(" Note only ", notes.findById(note.getId()).orElseThrow().getContent());
        assertTrue(service.build(owner.getId(), empty.getId(), "summary.v6").pages().isEmpty());
        assertEquals(input.fingerprint(), service.build(owner.getId(), material.getId(), "summary.v6").fingerprint());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.build(other.getId(), material.getId(), "v")).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.build(owner.getId(), UUID.randomUUID().toString(), "v")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.build(owner.getId(), " ", "v")).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.build(null, material.getId(), "v")).getStatusCode().value());
    }
}
