package com.onrelearn;

import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.MaterialPageTextRepository;
import com.onrelearn.material.service.ExtractionJobService;
import com.onrelearn.material.service.PdfTextExtractor;
import com.onrelearn.material.service.ExtractionJobService.Claim;
import com.onrelearn.material.service.ExtractionJobService.FailureCode;
import com.onrelearn.material.service.ExtractionLeaseLostException;
import com.onrelearn.material.service.ExtractionWorker;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.material.storage.StorageUnavailableException;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "onrelearn.material.extraction.lease=PT5M"})
class ExtractionJobClaimMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired ExtractionJobRepository jobs;
    @Autowired ExtractionJobService service;
    @Autowired ExtractionWorker worker;
    @Autowired PdfTextExtractor extractor;
    @MockitoBean PdfObjectStorage storage;
    @Autowired MaterialPageTextRepository pages;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private Fixture fixture;

    @BeforeEach
    void guardExistingQueue() {
        // ! 공유 개발 DB에서 실제 사용자 작업을 선점하지 않도록 기존 활성 작업이 있으면 시험을 중단한다.
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM extraction_job WHERE status IN ('PENDING', 'RUNNING')", Integer.class),
            "Queue tests require no pre-existing active jobs; no existing jobs were modified");
    }

    @Test
    void simultaneousWorkersClaimOneJobOnlyOnceAndCompletionCommits() throws Exception {
        seed(3, 0);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { start.await(); return service.claimNext("worker-a"); });
            var second = workers.submit(() -> { start.await(); return service.claimNext("worker-b"); });
            start.countDown();
            List<Claim> claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                .stream().flatMap(Optional::stream).toList();
            assertEquals(1, claims.size());
            Claim claim = claims.get(0);
            assertEquals(fixture.jobIds().get(0), claim.jobId());
            assertEquals(1, claim.attempt());
            assertTrue(service.claimNext("worker-c").isEmpty());
            service.complete(claim);
            assertEquals("DONE", status(claim.jobId()));
            assertThrows(ExtractionLeaseLostException.class, () -> service.complete(claim));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void lockedHighestPriorityJobDoesNotBlockAnotherPendingJob() throws Exception {
        seed(3, 100, 10);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var holder = workers.submit(() -> transaction().execute(state -> {
                jobs.lockById(fixture.jobIds().get(0)).orElseThrow();
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var available = workers.submit(() -> service.claimNext("unblocked-worker"));
            Claim claim = available.get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals(fixture.jobIds().get(1), claim.jobId());
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals("PENDING", status(fixture.jobIds().get(0)));
            assertEquals(0, attempts(fixture.jobIds().get(0)));
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiredAttemptCannotFinishAfterSameWorkerReclaimsAndThreeFailuresStop() {
        seed(3, 0);
        Claim old = service.claimNext("same-worker").orElseThrow();
        assertEquals(0, service.recoverExpired());
        expire(old.jobId());
        assertThrows(ExtractionLeaseLostException.class, () -> service.complete(old));
        assertEquals(1, service.recoverExpired());
        assertEquals("PENDING", status(old.jobId()));
        Claim second = service.claimNext("same-worker").orElseThrow();
        assertEquals(2, second.attempt());
        assertThrows(ExtractionLeaseLostException.class, () -> service.complete(old));
        assertThrows(ExtractionLeaseLostException.class, () -> service.fail(old, FailureCode.EXTRACTION_ERROR));
        Claim wrongWorker = new Claim(second.jobId(), second.materialId(), "other-worker", second.attempt());
        assertThrows(ExtractionLeaseLostException.class, () -> service.complete(wrongWorker));
        service.fail(second, FailureCode.STORAGE_UNAVAILABLE);
        Claim third = service.claimNext("same-worker").orElseThrow();
        assertEquals(3, third.attempt());
        service.fail(third, FailureCode.EXTRACTION_ERROR);
        assertEquals("FAILED", status(old.jobId()));
        assertEquals(3, attempts(old.jobId()));
        assertTrue(service.claimNext("same-worker").isEmpty());
    }

    @Test
    void lastAttemptLostOnRestartBecomesFailedAndCannotCompleteLate() {
        seed(1, 0);
        Claim old = service.claimNext("stopped-worker").orElseThrow();
        expire(old.jobId());
        assertEquals(1, service.recoverExpired());
        assertEquals("FAILED", status(old.jobId()));
        assertEquals(1, attempts(old.jobId()));
        assertEquals(0, service.recoverExpired());
        assertTrue(service.claimNext("restarted-worker").isEmpty());
        assertThrows(ExtractionLeaseLostException.class, () -> service.complete(old));
    }

    @Test
    void leaseRenewalKeepsCurrentAttemptAndRejectsExpiredOrOldAttempt() {
        seed(3, 0);
        Claim first = service.claimNext("renew-worker").orElseThrow();
        // * 합성 Job만 유효 lease의 끝에 가깝게 이동해 갱신 효과를 실제 DB 시각으로 확인한다.
        jdbc.update("UPDATE extraction_job SET locked_at = DATE_SUB(locked_at, INTERVAL 200 SECOND) WHERE id = ?",
            first.jobId());
        var beforeRenewal = jdbc.queryForObject("SELECT locked_at FROM extraction_job WHERE id = ?",
            java.sql.Timestamp.class, first.jobId());
        service.renew(first);
        assertEquals(1, attempts(first.jobId()));
        assertEquals("RUNNING", status(first.jobId()));
        assertEquals(0, service.recoverExpired());
        assertTrue(jdbc.queryForObject("SELECT locked_at FROM extraction_job WHERE id = ?",
            java.sql.Timestamp.class, first.jobId()).after(beforeRenewal));
        expire(first.jobId());
        assertThrows(ExtractionLeaseLostException.class, () -> service.renew(first));
        assertEquals(1, service.recoverExpired());
        Claim second = service.claimNext("renew-worker").orElseThrow();
        assertEquals(2, second.attempt());
        assertThrows(ExtractionLeaseLostException.class, () -> service.renew(first));
        service.renew(second);
        assertEquals(2, attempts(second.jobId()));
    }

    @Test
    void rollbackAndInvalidWorkerLeavePendingJobUntouched() {
        seed(3, 0);
        assertThrows(IllegalArgumentException.class, () -> service.claimNext(" "));
        transaction().execute(state -> {
            assertTrue(service.claimNext("rollback-worker").isPresent());
            state.setRollbackOnly();
            return null;
        });
        assertEquals("PENDING", status(fixture.jobIds().get(0)));
        assertEquals(0, attempts(fixture.jobIds().get(0)));
    }

    @Test
    void pageResultsCommitWithCompletionAndStaleAttemptCannotWrite() {
        seed(3, 0);
        Claim first = service.claimNext("page-worker").orElseThrow();
        var result = List.of(new PdfTextExtractor.PageText(1, "first"),
            new PdfTextExtractor.PageText(2, ""));
        assertThrows(IllegalArgumentException.class, () -> service.completeWithPages(first,
            List.of(new PdfTextExtractor.PageText(2, "wrong"))));
        assertEquals("RUNNING", status(first.jobId()));
        assertTrue(pages.findByMaterial_IdOrderByPageAsc(fixture.materialId()).isEmpty());
        transaction().execute(state -> {
            service.completeWithPages(first, result);
            state.setRollbackOnly();
            return null;
        });
        assertEquals("RUNNING", status(first.jobId()));
        assertTrue(pages.findByMaterial_IdOrderByPageAsc(fixture.materialId()).isEmpty());
        expire(first.jobId());
        assertEquals(1, service.recoverExpired());
        Claim second = service.claimNext("page-worker").orElseThrow();
        assertThrows(ExtractionLeaseLostException.class, () -> service.completeWithPages(first, result));
        assertTrue(pages.findByMaterial_IdOrderByPageAsc(fixture.materialId()).isEmpty());
        service.completeWithPages(second, result);
        assertEquals("DONE", status(second.jobId()));
        var saved = pages.findByMaterial_IdOrderByPageAsc(fixture.materialId());
        assertEquals(List.of(1, 2), saved.stream().map(page -> page.getPage()).toList());
        assertEquals(List.of("first", ""), saved.stream().map(page -> page.getText()).toList());
        assertThrows(ExtractionLeaseLostException.class, () -> service.completeWithPages(second, result));
    }

    @Test
    void internalWorkerDownloadsValidPdfAndCommitsPageAndDoneTogether() throws Exception {
        seed(3, 0);
        byte[] pdf = samplePdf("worker text");
        when(storage.download("synthetic/job.pdf", 20971520L)).thenReturn(pdf);
        assertTrue(worker.runOnce());
        assertEquals("DONE", status(fixture.jobIds().get(0)));
        assertEquals(1, attempts(fixture.jobIds().get(0)));
        var saved = pages.findByMaterial_IdOrderByPageAsc(fixture.materialId());
        assertEquals(1, saved.size());
        assertEquals(1, saved.get(0).getPage());
        assertTrue(saved.get(0).getText().contains("worker text"));
        assertEquals("PDFBOX", saved.get(0).getMethod());
        assertFalse(worker.runOnce());
        verify(storage, times(1)).download("synthetic/job.pdf", 20971520L);
    }

    @Test
    void internalWorkerClassifiesDownloadFailureAndCanRetry() throws Exception {
        seed(3, 0);
        when(storage.download("synthetic/job.pdf", 20971520L))
            .thenThrow(new StorageUnavailableException())
            .thenReturn(samplePdf("retry success"));
        assertTrue(worker.runOnce());
        assertEquals("PENDING", status(fixture.jobIds().get(0)));
        assertEquals(1, attempts(fixture.jobIds().get(0)));
        assertTrue(pages.findByMaterial_IdOrderByPageAsc(fixture.materialId()).isEmpty());
        assertEquals("STORAGE_UNAVAILABLE", jdbc.queryForObject(
            "SELECT last_error FROM extraction_job WHERE id = ?", String.class, fixture.jobIds().get(0)));
        assertTrue(worker.runOnce());
        assertEquals("DONE", status(fixture.jobIds().get(0)));
        assertEquals(2, attempts(fixture.jobIds().get(0)));
        assertEquals(1, pages.findByMaterial_IdOrderByPageAsc(fixture.materialId()).size());
    }

    @Test
    void processingDeadlineReturnsOwnedJobToPendingWithoutSavingPages() throws Exception {
        seed(3, 0);
        when(storage.download("synthetic/job.pdf", 20971520L)).thenAnswer(call -> {
            Thread.sleep(100);
            return samplePdf("late result");
        });
        var bounded = new ExtractionWorker(service, materials, storage, extractor, 20971520L, "PT0.05S");
        assertTrue(bounded.runOnce());
        assertEquals("PENDING", status(fixture.jobIds().get(0)));
        assertEquals(1, attempts(fixture.jobIds().get(0)));
        assertEquals("PROCESSING_TIMEOUT", jdbc.queryForObject(
            "SELECT last_error FROM extraction_job WHERE id = ?", String.class, fixture.jobIds().get(0)));
        assertTrue(pages.findByMaterial_IdOrderByPageAsc(fixture.materialId()).isEmpty());
    }

    private byte[] samplePdf(String text) throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
             var out = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private void seed(int maxAttempts, int... priorities) {
        fixture = transaction().execute(state -> {
            String email = "job-claim-" + UUID.randomUUID() + "@example.invalid";
            Member owner = members.saveAndFlush(new Member(email, "synthetic-hash"));
            Grade grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
            Subject subject = subjects.saveAndFlush(new Subject(grade, "Synthetic job claim"));
            Material material = materials.saveAndFlush(new Material(subject, 1, "Synthetic PDF", "synthetic/job.pdf"));
            List<String> ids = new ArrayList<>();
            for (int priority : priorities) ids.add(jobs.saveAndFlush(new ExtractionJob(material, priority, maxAttempts)).getId());
            return new Fixture(email, material.getId(), ids);
        });
    }

    private void expire(String jobId) {
        assertTrue(fixture.jobIds().contains(jobId));
        jdbc.update("UPDATE extraction_job SET locked_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE) WHERE id = ?", jobId);
    }

    private String status(String jobId) {
        return jdbc.queryForObject("SELECT status FROM extraction_job WHERE id = ?", String.class, jobId);
    }

    private int attempts(String jobId) {
        return jdbc.queryForObject("SELECT attempts FROM extraction_job WHERE id = ?", Integer.class, jobId);
    }

    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    @AfterEach
    void cleanupExactSyntheticRows() {
        if (fixture == null) return;
        // ! 동시성 시험 때문에 커밋한 합성 행만 정확한 식별자로 정리한다. 저장소에는 접근하지 않는다.
        assertTrue(fixture.email().matches("job-claim-[0-9a-f-]{36}@example\\.invalid"));
        transaction().execute(state -> {
            jdbc.update("DELETE FROM material_page_text WHERE material_id = ?", fixture.materialId());
            for (String id : fixture.jobIds()) jdbc.update("DELETE FROM extraction_job WHERE id = ? AND material_id = ?", id, fixture.materialId());
            jdbc.update("DELETE FROM material WHERE id = ?", fixture.materialId());
            jdbc.update("DELETE FROM app_user WHERE email = ?", fixture.email());
            return null;
        });
        assertFalse(members.existsByEmail(fixture.email()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM extraction_job WHERE material_id = ?", Integer.class, fixture.materialId()));
    }

    private record Fixture(String email, String materialId, List<String> jobIds) {}
}
