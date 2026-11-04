package com.onrelearn.material.repository;

import com.onrelearn.material.entity.Material;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<Material, String> {
    @org.springframework.data.jpa.repository.Query("select m.subject.id from Material m where m.id = :id and m.subject.grade.user.id = :memberId")
    Optional<String> findOwnedSubjectId(@org.springframework.data.repository.query.Param("id") String id,
        @org.springframework.data.repository.query.Param("memberId") String memberId);
    boolean existsBySubject_Id(String subjectId);
    boolean existsBySubject_IdAndWeek(String subjectId, int week);
    Optional<Material> findByIdAndSubject_Grade_User_Id(String id, String memberId);
    List<Material> findBySubject_IdOrderByWeekAscCreatedAtAsc(String subjectId);
}
