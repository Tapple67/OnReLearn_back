package com.onrelearn.subject.repository;

import com.onrelearn.subject.entity.Subject;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SubjectRepository extends JpaRepository<Subject, String> {
    Optional<Subject> findByIdAndGrade_User_Id(String id, String userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subject s where s.id = :id and s.grade.user.id = :userId")
    Optional<Subject> findOwnedForUpdate(@Param("id") String id, @Param("userId") String userId);
    List<Subject> findByGrade_IdOrderByCreatedAtDesc(String gradeId);
    @Query("""
        select s.id as id, s.name as name from Subject s
        where s.grade.user.id = :userId order by s.createdAt desc, s.id desc
        """)
    List<ProfileSubject> profileSubjects(@Param("userId") String userId);

    interface ProfileSubject {
        String getId();
        String getName();
    }
}
