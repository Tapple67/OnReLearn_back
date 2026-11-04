package com.onrelearn;

import com.onrelearn.member.repository.MemberRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("e2eCleanup")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SyntheticE2eCleanupTests {
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void removesOnlyTheNamedSyntheticAccount() {
        String email = System.getProperty("onrelearn.e2e.cleanup.email", "");
        assertTrue(email.matches("e2e-[0-9a-f-]{36}@example\\.invalid"),
            "Only an explicitly named synthetic E2E account can be cleaned up");
        var member = members.findByEmail(email).orElseThrow();
        var ownedMaterials = jdbc.queryForList("""
            SELECT m.id, m.storage_path, m.title, s.name AS subject_name
            FROM material m JOIN subject s ON s.id = m.subject_id
            JOIN grade g ON g.id = s.grade_id WHERE g.user_id = ?
            """, member.getId());
        // ! Only the known metadata-only PDF fixture may be removed before its account.
        assertTrue(ownedMaterials.size() <= 1, "Unexpected synthetic materials");
        if (!ownedMaterials.isEmpty()) {
            var material = ownedMaterials.get(0);
            String path = (String) material.get("storage_path");
            String expectedRecordPath = "users/" + member.getId() + "/materials/" + material.get("id") + ".pdf";
            assertTrue("users/synthetic/progress.pdf".equals(path) || expectedRecordPath.equals(path),
                "Only the known synthetic metadata-only material may be removed");
            assertEquals("Synthetic progress", material.get("title"));
            assertEquals("Synthetic progress", material.get("subject_name"));
            assertEquals(1, jdbc.update("DELETE FROM material WHERE id = ?", material.get("id")));
        }
        int removed = jdbc.update("DELETE FROM app_user WHERE id = ? AND email = ?", member.getId(), email);
        assertEquals(1, removed);
        assertFalse(members.existsById(member.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE user_id = ?", Long.class, member.getId()));
        // * A failed assertion rolls back; commit only after every cleanup check passes.
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }
}
