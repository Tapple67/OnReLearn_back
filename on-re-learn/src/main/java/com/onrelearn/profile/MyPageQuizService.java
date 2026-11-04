package com.onrelearn.profile;

import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.QuizAnswerRepository;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MyPageQuizService {
    private static final List<QuizAttemptStatus> COMPLETED =
        List.of(QuizAttemptStatus.SUBMITTED, QuizAttemptStatus.GRADED);
    private final SubjectRepository subjects;
    private final QuizAttemptRepository attempts;
    private final QuizAnswerRepository answers;

    public MyPageQuizService(SubjectRepository subjects, QuizAttemptRepository attempts,
            QuizAnswerRepository answers) {
        this.subjects = subjects;
        this.attempts = attempts;
        this.answers = answers;
    }

    // * Every projection comes from one owner-scoped read snapshot; no activity history is inferred here.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyPageQuizOverview get(String userId, Instant now) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        var ownedSubjects = subjects.profileSubjects(userId).stream()
            .map(s -> new MyPageQuizOverview.Subject(s.getId(), s.getName())).toList();
        var completed = attempts.profileAttempts(userId, COMPLETED).stream()
            .map(a -> new MyPageQuizOverview.Attempt(a.getId(), a.getSubjectId(), a.getSubjectName(),
                a.getMaterialTitle(), a.getQuizTitle(), a.getStatus(), a.getScore(), a.getMaxScore(),
                a.getStartedAt(), a.getSubmittedAt(), a.getGradedAt())).toList();
        long graded = answers.profileGradedAnswerCount(userId, COMPLETED);
        long correct = answers.profileCorrectAnswerCount(userId, COMPLETED);
        return MyPageQuizOverview.from(ownedSubjects, completed, correct, graded, now);
    }
}
