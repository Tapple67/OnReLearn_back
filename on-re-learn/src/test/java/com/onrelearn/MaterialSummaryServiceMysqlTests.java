package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.note.entity.Note;
import com.onrelearn.note.repository.NoteRepository;
import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.service.MaterialSummaryService;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class MaterialSummaryServiceMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired NoteRepository notes;
    @Autowired MaterialSummaryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SummaryProvider provider;

    @Test
    void reusesRegeneratesAndPreservesExistingSummaryOnFailureOrSourceChange() throws Exception {
        var tx = new TransactionTemplate(transactions);
        String email = UUID.randomUUID() + "@example.invalid";
        String[] ids = new String[3];
        try {
            tx.executeWithoutResult(status -> {
                var owner = members.saveAndFlush(new Member(email, "synthetic-hash"));
                ids[0] = owner.getId();
                var grade = grades.saveAndFlush(new Grade(owner, 1, 1, true));
                var subject = subjects.saveAndFlush(new Subject(grade, "Summary service"));
                var material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/test.pdf"));
                ids[1] = material.getId();
                ids[2] = notes.saveAndFlush(new Note(material, 1, "First note")).getId();
            });
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return result("First");
            }).when(provider).generate(any());
            var first = service.ensure(ids[0], ids[1]);
            assertFalse(first.reused());
            assertEquals("First", first.content());
            assertTrue(service.ensure(ids[0], ids[1]).reused());
            verify(provider, times(1)).generate(any());
            assertStatus(403, () -> service.ensure(UUID.randomUUID().toString(), ids[1]));
            assertStatus(401, () -> service.ensure(null, ids[1]));
            assertStatus(400, () -> service.ensure(ids[0], " "));
            verify(provider, times(1)).generate(any());

            jdbc.update("UPDATE note SET content = ? WHERE id = ?", "Changed", ids[2]);
            when(provider.generate(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Synthetic failure"));
            assertStatus(502, () -> service.ensure(ids[0], ids[1]));
            assertEquals("First", stored(ids[1]));
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                jdbc.update("UPDATE note SET content = ? WHERE id = ?", "Changed during generation", ids[2]);
                return result("Stale");
            }).when(provider).generate(any());
            assertStatus(409, () -> service.ensure(ids[0], ids[1]));
            assertEquals("First", stored(ids[1]));
            doReturn(result("Updated")).when(provider).generate(any());
            var updated = service.ensure(ids[0], ids[1]);
            assertEquals(first.summaryId(), updated.summaryId());
            assertFalse(updated.reused());
            assertEquals("Updated", stored(ids[1]));

            // * Concurrent first saves share the subject lock and commit just one summary.
            jdbc.update("DELETE FROM material_summary WHERE material_id = ?", ids[1]);
            var barrier = new CyclicBarrier(2);
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                barrier.await(10, TimeUnit.SECONDS);
                return result("Concurrent");
            }).when(provider).generate(any());
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> service.ensure(ids[0], ids[1]));
                var b = pool.submit(() -> service.ensure(ids[0], ids[1]));
                var av = a.get(20, TimeUnit.SECONDS);
                var bv = b.get(20, TimeUnit.SECONDS);
                assertEquals(av.summaryId(), bv.summaryId());
                assertNotEquals(av.reused(), bv.reused());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM material_summary WHERE material_id = ?", Integer.class, ids[1]));
        } finally {
            // ! Delete only this test's synthetic material and account; cascades clean their children.
            if (ids[1] != null) jdbc.update("DELETE FROM material WHERE id = ?", ids[1]);
            jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        }
    }
    private String stored(String materialId) {
        return jdbc.queryForObject("SELECT content FROM material_summary WHERE material_id = ?", String.class, materialId);
    }
    private static SummaryResult result(String content) {
        return new SummaryResult(content, null, null, "synthetic", "synthetic-model", SummaryPrompt.VERSION);
    }
    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
}
