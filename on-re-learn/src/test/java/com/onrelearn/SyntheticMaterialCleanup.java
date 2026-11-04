package com.onrelearn;

import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

// ! 이식 시험이 생성한 자료만 자식부터 정리한다. 운영 삭제 정책이나 DB 제약은 변경하지 않는다.
final class SyntheticMaterialCleanup {
    static void delete(JdbcTemplate jdbc, String materialId) {
        if (materialId == null) return;
        var emails = jdbc.queryForList("SELECT u.email FROM material m JOIN subject s ON s.id=m.subject_id JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE m.id=?", String.class, materialId);
        if (emails.isEmpty()) return;
        assertEquals(1, emails.size());
        assertTrue(emails.get(0).matches("(summary-api|quiz-api|quiz-read|material-upload)-[0-9a-f-]{36}@example\\.invalid"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM extraction_job WHERE material_id=? AND status='RUNNING'", Integer.class, materialId));
        jdbc.update("DELETE a FROM quiz_answer a JOIN quiz_attempt t ON t.id=a.attempt_id JOIN quiz_set s ON s.id=t.quiz_set_id WHERE s.material_id=?", materialId);
        jdbc.update("DELETE a FROM quiz_answer a JOIN quiz_item i ON i.id=a.quiz_item_id JOIN quiz_set s ON s.id=i.quiz_set_id WHERE s.material_id=?", materialId);
        jdbc.update("DELETE t FROM quiz_attempt t JOIN quiz_set s ON s.id=t.quiz_set_id WHERE s.material_id=?", materialId);
        jdbc.update("DELETE i FROM quiz_item i JOIN quiz_set s ON s.id=i.quiz_set_id WHERE s.material_id=?", materialId);
        jdbc.update("DELETE FROM quiz_set WHERE material_id=?", materialId);
        jdbc.update("DELETE FROM material_summary WHERE material_id=?", materialId);
        jdbc.update("DELETE FROM note WHERE material_id=?", materialId);
        jdbc.update("DELETE FROM material_page_text WHERE material_id=?", materialId);
        jdbc.update("DELETE FROM extraction_job WHERE material_id=?", materialId);
        jdbc.update("DELETE FROM material WHERE id=?", materialId);
    }
}
