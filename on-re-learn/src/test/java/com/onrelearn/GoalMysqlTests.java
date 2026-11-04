package com.onrelearn;

import com.onrelearn.goal.entity.Goal;
import com.onrelearn.goal.repository.GoalRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class GoalMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GoalRepository goals;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void goalDefaultsUpdatesAndConstraintsPreserveOwnerData() {
        // * All rows in this test are synthetic and roll back with the transaction.
        Member owner = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        Goal goal = goals.saveAndFlush(new Goal(owner));
        assertEquals(10, goal.getDailyQuizGoal());
        assertEquals(70, goal.getWeeklyQuizGoal());
        assertEquals(3, goal.getDailyMaterialGoal());
        assertEquals(15, goal.getWeeklyMaterialGoal());
        String goalId = goal.getId();
        entityManager.clear();
        Goal persisted = goals.findById(goalId).orElseThrow();
        var createdAt = persisted.getCreatedAt();

        persisted.update(0, 2, 4, 0);
        goals.flush();
        entityManager.clear();
        Goal loaded = goals.findByUser_Id(owner.getId()).orElseThrow();
        assertEquals(0, loaded.getDailyQuizGoal());
        assertEquals(2, loaded.getWeeklyQuizGoal());
        assertEquals(4, loaded.getDailyMaterialGoal());
        assertEquals(0, loaded.getWeeklyMaterialGoal());
        assertEquals(createdAt, loaded.getCreatedAt());
        assertThrows(IllegalArgumentException.class, () -> loaded.update(-1, 2, 4, 0));
        assertEquals(0, loaded.getDailyQuizGoal());

        // ! Database constraints also protect direct writes and one-goal-per-user ownership.
        String insert = "INSERT INTO goal (id, user_id, daily_quiz_goal, weekly_quiz_goal, daily_material_goal, weekly_material_goal, created_at, updated_at) VALUES (?, ?, ?, 70, 3, 15, NOW(6), NOW(6))";
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update(insert, UUID.randomUUID().toString(), owner.getId(), 10));
        assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update(insert, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 10));
        Member other = members.saveAndFlush(new Member(UUID.randomUUID() + "@example.invalid", "synthetic-hash"));
        assertThrows(DataAccessException.class,
            () -> jdbc.update(insert, UUID.randomUUID().toString(), other.getId(), -1));
        jdbc.update("DELETE FROM app_user WHERE id = ?", owner.getId());
        entityManager.clear();
        assertTrue(goals.findById(goalId).isEmpty());
    }
}
