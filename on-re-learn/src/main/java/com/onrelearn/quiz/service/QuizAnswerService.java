package com.onrelearn.quiz.service;

import com.onrelearn.quiz.entity.QuizAnswer;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.*;
import com.onrelearn.subject.repository.SubjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuizAnswerService {
    private final QuizAttemptRepository attempts;
    private final QuizItemRepository items;
    private final QuizAnswerRepository answers;
    private final SubjectRepository subjects;
    private final JsonMapper json=JsonMapper.builder().build();

    public QuizAnswerService(QuizAttemptRepository attempts,QuizItemRepository items,
            QuizAnswerRepository answers,SubjectRepository subjects) {
        this.attempts=attempts; this.items=items; this.answers=answers; this.subjects=subjects;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void save(String userId,String attemptId,String itemId,JsonNode response) {
        if(userId==null || userId.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(attemptId==null || attemptId.isBlank() || itemId==null || itemId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attempt and item IDs are required");
        String id=attemptId.trim();
        String subjectId=attempts.findOwnedSubjectId(id,userId).orElseThrow(this::unavailable);
        // ! Match start/delete lock order; load state only after locking to serialize upserts.
        subjects.findOwnedForUpdate(subjectId,userId).orElseThrow(this::unavailable);
        var attempt=attempts.findByIdAndUserId(id,userId).orElseThrow(this::unavailable);
        if(attempt.getStatus()!=QuizAttemptStatus.IN_PROGRESS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Quiz already submitted");
        var item=items.findById(itemId.trim()).filter(i -> i.getQuizSet().getId().equals(attempt.getQuizSet().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Quiz item unavailable"));
        var choices=item.getChoices()==null ? null : json.readTree(item.getChoices());
        QuizAnswerInput.validate(item.getType(),choices,response);
        String encoded=json.writeValueAsString(response);
        var answer=answers.findByAttempt_IdAndItem_Id(id,item.getId())
                .orElseGet(() -> new QuizAnswer(attempt,item,encoded));
        answer.replaceResponse(encoded);
        answers.saveAndFlush(answer);
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,"Quiz unavailable");
    }
}
