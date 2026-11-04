package com.onrelearn.quiz.repository;
import com.onrelearn.quiz.entity.QuizSet;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuizSetRepository extends JpaRepository<QuizSet, String> {
    @org.springframework.data.jpa.repository.Query("select s.material.subject.id from QuizSet s where s.id = :id and s.material.subject.grade.user.id = :userId")
    java.util.Optional<String> findOwnedSubjectId(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("userId") String userId);
    java.util.Optional<QuizSet> findByIdAndMaterial_Subject_Grade_User_Id(String id,String userId);
    // * Count in SQL without loading private answer-bearing item entities.
    @org.springframework.data.jpa.repository.Query("""
        select s.id as id, s.title as title, s.createdAt as createdAt, count(i) as itemCount, s.promptVersion as promptVersion
        from QuizSet s left join QuizItem i on i.quizSet = s
        where s.material.id = :materialId
        group by s.id, s.title, s.createdAt, s.promptVersion
        order by s.createdAt desc, s.id desc
        """)
    java.util.List<ListEntry> listForMaterial(@org.springframework.data.repository.query.Param("materialId") String materialId);

    interface ListEntry {
        String getId();
        String getTitle();
        String getPromptVersion();
        java.time.Instant getCreatedAt();
        long getItemCount();
    }

    java.util.List<QuizSet> findByMaterial_IdOrderByCreatedAtDesc(String materialId);
}
