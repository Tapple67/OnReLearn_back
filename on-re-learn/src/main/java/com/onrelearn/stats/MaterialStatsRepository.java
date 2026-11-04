package com.onrelearn.stats;

import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.model.QuizPurpose;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MaterialStatsRepository extends Repository<QuizAttempt, String> {
    // ! Select statistics only: no response text, answer keys or explanations.
    // Left joins retain completed attempts with no answers.
    @Query("""
        select a.id as attemptId, a.status as status, a.score as score,
               a.maxScore as maxScore, a.submittedAt as submittedAt, s.purpose as purpose,
               i.id as itemId, i.type as type, i.topic as topic, r.isCorrect as correct
        from QuizAttempt a join a.quizSet s
        left join QuizAnswer r on r.attempt = a
        left join r.item i on i.quizSet = s
        where a.userId = :userId and s.material.id = :materialId
          and s.material.subject.grade.user.id = :userId
          and a.status in :statuses
        order by s.createdAt, s.id, a.startedAt, a.id, r.createdAt, r.id
        """)
    List<Row> completedRows(@Param("userId") String userId, @Param("materialId") String materialId,
                           @Param("statuses") List<QuizAttemptStatus> statuses);

    interface Row {
        String getAttemptId();
        QuizAttemptStatus getStatus();
        Integer getScore();
        Integer getMaxScore();
        Instant getSubmittedAt();
        QuizPurpose getPurpose();
        String getItemId();
        String getType();
        String getTopic();
        Boolean getCorrect();
    }
}
