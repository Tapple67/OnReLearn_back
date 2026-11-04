package com.onrelearn.quiz.repository;
import com.onrelearn.quiz.entity.QuizAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuizAnswerRepository extends JpaRepository<QuizAnswer,String> {
    @org.springframework.data.jpa.repository.Query("""
        select count(a) from QuizAnswer a
        where a.attempt.userId = :userId and a.attempt.quizSet.material.subject.grade.user.id = :userId
          and a.attempt.status in :statuses and a.isCorrect is not null
        """)
    long profileGradedAnswerCount(
        @org.springframework.data.repository.query.Param("userId") String userId,
        @org.springframework.data.repository.query.Param("statuses")
            java.util.List<com.onrelearn.quiz.model.QuizAttemptStatus> statuses);

    @org.springframework.data.jpa.repository.Query("""
        select count(a) from QuizAnswer a
        where a.attempt.userId = :userId and a.attempt.quizSet.material.subject.grade.user.id = :userId
          and a.attempt.status in :statuses and a.isCorrect = true
        """)
    long profileCorrectAnswerCount(
        @org.springframework.data.repository.query.Param("userId") String userId,
        @org.springframework.data.repository.query.Param("statuses")
            java.util.List<com.onrelearn.quiz.model.QuizAttemptStatus> statuses);
    java.util.List<QuizAnswer> findByAttempt_Id(String attemptId);
    java.util.Optional<QuizAnswer> findByAttempt_IdAndItem_Id(String attemptId,String itemId);
}
