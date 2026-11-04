package com.onrelearn;

import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class ExtractionJobMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired ExtractionJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void retriesCountActualClaimsAndFinalFailureIsPreserved() {
        // * 합성 자료와 작업은 실제 MySQL 트랜잭션에서만 만들고 시험 후 롤백한다.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Jobs"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "PDF", "users/synthetic/materials/job.pdf"));
        ExtractionJob job = jobs.saveAndFlush(new ExtractionJob(material, 0, 3));
        assertEquals(1, jobs.findByMaterial_IdOrderByCreatedAtDesc(material.getId()).size());

        Instant now = Instant.now();
        for (int expectedAttempt = 1; expectedAttempt <= 3; expectedAttempt++) {
            Instant claimAt = now.plus(expectedAttempt, ChronoUnit.MINUTES);
            job.claim("worker-one", claimAt, claimAt.minus(1, ChronoUnit.MINUTES));
            assertEquals(expectedAttempt, job.getAttempts());
            assertThrows(IllegalStateException.class, () -> job.complete("other-worker", claimAt));
            job.fail("worker-one", claimAt, "EXTRACTION_ERROR");
            assertEquals(expectedAttempt == 3 ? "FAILED" : "PENDING", job.getStatus());
        }
        jobs.flush();
        assertEquals(3, jdbc.queryForObject("SELECT attempts FROM extraction_job WHERE id = ?", Integer.class, job.getId()));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM extraction_job WHERE id = ?", String.class, job.getId()));
        assertNull(job.getLockedBy());
        assertFalse(job.canClaim(now));
        assertThrows(IllegalStateException.class, () -> job.claim("worker-one", now, now));

        // ! DB FK도 서비스 밖에서 작업의 소유 자료를 실수로 삭제하지 못하게 한다.
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("DELETE FROM material WHERE id = ?", material.getId()));
    }

    @Test @Transactional
    void staleFinalLeaseCanBeTerminatedWithoutFourthAttempt() {
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
        Subject subject = subjects.saveAndFlush(new Subject(grade, "Lease"));
        Material material = materials.saveAndFlush(new Material(subject, 1, "PDF", "users/synthetic/materials/lease.pdf"));
        ExtractionJob job = jobs.saveAndFlush(new ExtractionJob(material, 0, 1));
        Instant claimed = Instant.now().minus(2, ChronoUnit.MINUTES);
        job.claim("old-worker", claimed, claimed.minus(1, ChronoUnit.MINUTES));
        assertThrows(IllegalStateException.class, () -> job.expireLease(Instant.now(), claimed.minusSeconds(1)));
        job.expireLease(Instant.now(), claimed.plusSeconds(1));
        jobs.flush();
        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getAttempts());
        assertEquals("LEASE_EXPIRED", job.getLastError());
        assertNull(job.getLockedAt());
    }
}
