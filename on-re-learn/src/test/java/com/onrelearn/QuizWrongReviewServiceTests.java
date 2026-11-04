package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.quiz.entity.QuizAnswer;
import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.entity.QuizItem;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.QuizAnswerRepository;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.quiz.service.QuizWrongReviewService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizWrongReviewServiceTests {
    @Test void submittedWrongAnswerIsLoadedAndReadFailureIsNotTreatedAsEmpty() {
        var attempts = mock(QuizAttemptRepository.class);
        var answers = mock(QuizAnswerRepository.class);
        var attempt = mock(QuizAttempt.class);
        var set = mock(QuizSet.class);
        var material = mock(Material.class);
        var answer = mock(QuizAnswer.class);
        var item = mock(QuizItem.class);
        when(attempts.findByIdAndUserIdAndQuizSet_Material_Subject_Grade_User_Id("attempt", "owner", "owner"))
                .thenReturn(Optional.of(attempt));
        when(attempt.getQuizSet()).thenReturn(set);
        when(attempt.getStatus()).thenReturn(QuizAttemptStatus.SUBMITTED);
        when(attempt.getId()).thenReturn("attempt");
        when(set.getMaterial()).thenReturn(material);
        when(material.getId()).thenReturn("material");
        when(answers.findByAttempt_Id("attempt")).thenReturn(List.of(answer));
        when(answer.getIsCorrect()).thenReturn(false);
        when(answer.getItem()).thenReturn(item);
        when(item.getId()).thenReturn("item");
        when(item.getQuestion()).thenReturn("Question");
        when(item.getTopic()).thenReturn("Vectors");

        var service = new QuizWrongReviewService(attempts, answers);
        var source = service.load("owner", "material", "attempt");
        assertEquals(List.of("item"), source.sourceItemIds());
        assertEquals(List.of("Vectors"), source.focus().topics());

        when(answers.findByAttempt_Id("attempt"))
                .thenThrow(new DataAccessResourceFailureException("synthetic read failure"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.load("owner", "material", "attempt"));
    }
}
