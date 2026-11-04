package com.onrelearn.profile;

import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecentQuizService {
    private final QuizAttemptRepository attempts;

    public RecentQuizService(QuizAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public List<RecentQuiz> get(String userId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        // ! The query checks both attempt ownership and the material's current owner.
        return attempts.recentForUser(userId, PageRequest.of(0, 5)).stream()
            .map(a -> new RecentQuiz(a.getId(), a.getQuizSetId(), a.getTitle(),
                a.getStatus(), a.getStartedAt()))
            .toList();
    }

    public record RecentQuiz(String attemptId, String quizSetId, String title,
                             QuizAttemptStatus status, Instant startedAt) {}
}
