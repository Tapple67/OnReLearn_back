package com.onrelearn.quiz.service;

import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuizSetDeletionService {
    private final QuizSetRepository sets;
    private final SubjectRepository subjects;

    public QuizSetDeletionService(QuizSetRepository sets, SubjectRepository subjects) {
        this.sets = sets;
        this.subjects = subjects;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String userId, String quizSetId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (quizSetId == null || quizSetId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiz set ID is required");
        String id = quizSetId.trim();
        String subjectId = sets.findOwnedSubjectId(id, userId).orElseThrow(this::unavailable);
        // ! Use the same subject lock as answer writes and submission before deleting their parent set.
        subjects.findOwnedForUpdate(subjectId, userId).orElseThrow(this::unavailable);
        var set = sets.findByIdAndMaterial_Subject_Grade_User_Id(id, userId).orElseThrow(this::unavailable);
        // * The DB's ON DELETE CASCADE removes items, attempts, and answers in one transaction.
        sets.delete(set);
        sets.flush();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Quiz unavailable");
    }
}
