package com.onrelearn.material.repository;

import com.onrelearn.material.entity.Material;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialContentDeletionRepository extends Repository<Material, String> {
    @Modifying @Query(value="DELETE a FROM quiz_answer a JOIN quiz_attempt t ON t.id=a.attempt_id JOIN quiz_set s ON s.id=t.quiz_set_id WHERE s.material_id=:id", nativeQuery=true)
    void deleteAttemptAnswers(@Param("id") String id);
    @Modifying @Query(value="DELETE a FROM quiz_answer a JOIN quiz_item i ON i.id=a.quiz_item_id JOIN quiz_set s ON s.id=i.quiz_set_id WHERE s.material_id=:id", nativeQuery=true)
    void deleteItemAnswers(@Param("id") String id);
    @Modifying @Query(value="DELETE t FROM quiz_attempt t JOIN quiz_set s ON s.id=t.quiz_set_id WHERE s.material_id=:id", nativeQuery=true)
    void deleteAttempts(@Param("id") String id);
    @Modifying @Query(value="DELETE i FROM quiz_item i JOIN quiz_set s ON s.id=i.quiz_set_id WHERE s.material_id=:id", nativeQuery=true)
    void deleteItems(@Param("id") String id);
    @Modifying @Query(value="DELETE FROM quiz_set WHERE material_id=:id", nativeQuery=true)
    void deleteSets(@Param("id") String id);
    @Modifying @Query(value="DELETE FROM material_summary WHERE material_id=:id", nativeQuery=true)
    void deleteSummary(@Param("id") String id);
    @Modifying @Query(value="DELETE FROM note WHERE material_id=:id", nativeQuery=true)
    void deleteNotes(@Param("id") String id);
    @Modifying @Query(value="DELETE FROM material_page_text WHERE material_id=:id", nativeQuery=true)
    void deletePageTexts(@Param("id") String id);
}
