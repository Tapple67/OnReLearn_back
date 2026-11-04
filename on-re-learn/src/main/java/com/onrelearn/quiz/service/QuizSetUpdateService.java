package com.onrelearn.quiz.service;

import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuizSetUpdateService {
    private final QuizSetRepository sets;
    private final SubjectRepository subjects;

    public QuizSetUpdateService(QuizSetRepository sets, SubjectRepository subjects) {
        this.sets = sets;
        this.subjects = subjects;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Updated rename(String userId, String quizSetId, String title) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (quizSetId == null || quizSetId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiz set ID is required");
        // * Match JavaScript trim and UTF-16 length in the legacy title contract.
        String normalized = title == null ? "" : title.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (normalized.isEmpty() || normalized.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiz title must contain 1 to 100 characters");
        String id = quizSetId.trim();
        String subjectId = sets.findOwnedSubjectId(id, userId).orElseThrow(this::unavailable);
        // ! Share the material-delete lock order and recheck ownership after acquiring the lock.
        subjects.findOwnedForUpdate(subjectId, userId).orElseThrow(this::unavailable);
        var set = sets.findByIdAndMaterial_Subject_Grade_User_Id(id, userId).orElseThrow(this::unavailable);
        set.rename(normalized);
        sets.saveAndFlush(set);
        return new Updated(set.getId(), set.getTitle(), set.getCreatedAt());
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Quiz unavailable");
    }

    public record Updated(String id, String title, Instant createdAt) {}
}
