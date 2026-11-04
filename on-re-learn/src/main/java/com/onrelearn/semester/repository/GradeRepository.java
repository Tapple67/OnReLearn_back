package com.onrelearn.semester.repository;

import com.onrelearn.semester.entity.Grade;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeRepository extends JpaRepository<Grade, String> {
    List<Grade> findByUser_IdOrderByYearAscTermAsc(String userId);
    Optional<Grade> findByIdAndUser_Id(String id, String userId);

    @Modifying
    @Query("update Grade grade set grade.current = false where grade.user.id = :userId and grade.current = true")
    void clearCurrentForUser(@Param("userId") String userId);
}
