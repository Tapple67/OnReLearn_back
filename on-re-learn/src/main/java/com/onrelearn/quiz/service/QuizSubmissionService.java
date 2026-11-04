package com.onrelearn.quiz.service;

import com.onrelearn.quiz.entity.QuizAnswer;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.*;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.util.HashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuizSubmissionService {
    private final QuizAttemptRepository attempts;
    private final QuizItemRepository items;
    private final QuizAnswerRepository answers;
    private final SubjectRepository subjects;
    private final JsonMapper json=JsonMapper.builder().build();
    public QuizSubmissionService(QuizAttemptRepository attempts,QuizItemRepository items,
            QuizAnswerRepository answers,SubjectRepository subjects) {
        this.attempts=attempts; this.items=items; this.answers=answers; this.subjects=subjects;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Submitted submit(String userId,String attemptId) {
        if(userId==null || userId.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(attemptId==null || attemptId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attempt ID is required");
        String id=attemptId.trim();
        String subjectId=attempts.findOwnedSubjectId(id,userId).orElseThrow(this::unavailable);
        // ! Serialize with answer saves and material deletion before loading managed attempt state.
        subjects.findOwnedForUpdate(subjectId,userId).orElseThrow(this::unavailable);
        var attempt=attempts.findByIdAndUserId(id,userId).orElseThrow(this::unavailable);
        if(attempt.getStatus()!=QuizAttemptStatus.IN_PROGRESS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Quiz already submitted");
        var existing=new HashMap<String,QuizAnswer>();
        for(var answer:answers.findByAttempt_Id(id)) existing.put(answer.getItem().getId(),answer);
        long rawScore=0,rawMax=0;
        for(var item:items.findByQuizSet_IdOrderByPositionAsc(attempt.getQuizSet().getId())) {
            var answer=existing.get(item.getId());
            var response=answer==null ? json.createObjectNode() : json.readTree(answer.getResponse());
            if(response.isNull()) response=json.createObjectNode();
            var grade=QuizGrading.grade(item.getType(),item.getPoints(),json.readTree(item.getAnswerKey()),response);
            rawScore+=grade.earned(); rawMax+=item.getPoints();
            if(answer==null) answer=new QuizAnswer(attempt,item,json.writeValueAsString(response));
            else if(json.readTree(answer.getResponse()).isNull()) answer.replaceResponse("{}");
            answer.recordGrade(grade.isCorrect(),grade.earned());
            answers.save(answer);
        }
        int maxScore=rawMax>0 ? 100 : 0;
        int score=rawMax>0 ? (int)Math.round((double)rawScore/rawMax*100) : 0;
        attempt.completeGrading(score,maxScore,Instant.now());
        // ! Any flush/commit failure rolls back both newly created answers and the attempt transition.
        attempts.saveAndFlush(attempt);
        return new Submitted(id,score,maxScore);
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,"Quiz unavailable");
    }
    public record Submitted(String attemptId,int score,int maxScore) {}
}
