package com.onrelearn.goal.repository;

import com.onrelearn.goal.entity.Goal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, String> {
    Optional<Goal> findByUser_Id(String userId);
}
