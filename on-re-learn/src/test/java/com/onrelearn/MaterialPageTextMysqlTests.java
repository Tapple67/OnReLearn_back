package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.entity.MaterialPageText;
import com.onrelearn.material.repository.MaterialPageTextRepository;
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
class MaterialPageTextMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired MaterialPageTextRepository pageTexts;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void pageTextPersistsUpdatesAndKeepsMaterialPageUnique() {
        // * 합성 행은 실제 onrelearn MySQL 트랜잭션에서만 만들고 시험 종료 시 롤백한다.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "PDF pages"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/materials/test.pdf"));
        MaterialPageText page = pageTexts.saveAndFlush(new MaterialPageText(material, 1, "첫 페이지", "pdfbox"));

        assertEquals("첫 페이지", pageTexts.findByMaterial_IdAndPage(material.getId(), 1).orElseThrow().getText());
        assertEquals("첫 페이지".length(), page.getCharCount());
        assertEquals("DONE", page.getStatus());
        page.replaceExtractedText("바뀐 내용", "pdfbox");
        pageTexts.flush();
        assertEquals("바뀐 내용", jdbc.queryForObject(
            "SELECT text FROM material_page_text WHERE id = ?", String.class, page.getId()));
        assertEquals(1, pageTexts.findByMaterial_IdOrderByPageAsc(material.getId()).size());

        // ! 유일성과 FK는 서비스 외부의 직접 JDBC 쓰기에도 적용되어야 한다.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO material_page_text (id, material_id, page, text, char_count, status, extracted_at, updated_at) "
                + "VALUES (?, ?, 1, '', 0, 'DONE', NOW(6), NOW(6))",
            UUID.randomUUID().toString(), material.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO material_page_text (id, material_id, page, text, char_count, status, extracted_at, updated_at) "
                + "VALUES (?, ?, 1, '', 0, 'DONE', NOW(6), NOW(6))",
            UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "INSERT INTO material_page_text (id, material_id, page, text, char_count, status, extracted_at, updated_at) "
                + "VALUES (?, ?, 0, '', 0, 'DONE', NOW(6), NOW(6))",
            UUID.randomUUID().toString(), material.getId()));
    }
}
