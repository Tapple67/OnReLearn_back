package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.subject.service.SubjectService;
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
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class MaterialMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired SubjectService subjectService;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void materialRelationWeekUniquenessAndDeleteProtectionUseRealMysql() {
        // * 합성 행은 시험 트랜잭션에서만 생성하고 종료 시 모두 롤백한다.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Member other = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Database"));
        Material material = materials.saveAndFlush(Material.builder()
            .subject(subject).week(1).title(" Week One ").storagePath("users/synthetic/materials/one.pdf").build());

        assertEquals("Week One", material.getTitle());
        assertTrue(materials.existsBySubject_Id(subject.getId()));
        assertTrue(materials.findByIdAndSubject_Grade_User_Id(material.getId(), owner.getId()).isPresent());
        assertTrue(materials.findByIdAndSubject_Grade_User_Id(material.getId(), other.getId()).isEmpty());
        material.rename("Updated");
        material.moveToWeek(2);
        materials.flush();
        assertEquals("Updated", jdbc.queryForObject("SELECT title FROM material WHERE id = ?", String.class, material.getId()));
        assertEquals(2, materials.findBySubject_IdOrderByWeekAscCreatedAtAsc(subject.getId()).get(0).getWeek());

        var blocked = assertThrows(ResponseStatusException.class,
            () -> subjectService.delete(owner.getId(), subject.getId()));
        assertEquals(409, blocked.getStatusCode().value());
        assertTrue(subjects.existsById(subject.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO material (id, subject_id, week, title, storage_path, created_at) VALUES (?, ?, 2, 'Duplicate', 'synthetic.pdf', NOW(6))",
            UUID.randomUUID().toString(), subject.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO material (id, subject_id, week, title, storage_path, created_at) VALUES (?, ?, 1, 'Orphan', 'synthetic.pdf', NOW(6))",
            UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "INSERT INTO material (id, subject_id, week, title, storage_path, created_at) VALUES (?, ?, 0, 'Invalid', 'synthetic.pdf', NOW(6))",
            UUID.randomUUID().toString(), subject.getId()));
        // ! DB 외래 키도 Service를 거치지 않는 과목 삭제를 막아 파일 메타데이터를 보존한다.
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("DELETE FROM subject WHERE id = ?", subject.getId()));
    }
}
