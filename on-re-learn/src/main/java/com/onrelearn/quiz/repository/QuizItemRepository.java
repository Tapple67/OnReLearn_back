package com.onrelearn.quiz.repository;
import com.onrelearn.quiz.entity.QuizItem;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuizItemRepository extends JpaRepository<QuizItem, String> {
    java.util.List<QuizItem> findByQuizSet_IdOrderByPositionAsc(String quizSetId);
}
