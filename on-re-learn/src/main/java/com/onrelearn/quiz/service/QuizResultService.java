package com.onrelearn.quiz.service;

import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.*;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuizResultService {
    private final QuizAttemptRepository attempts;
    private final QuizItemRepository items;
    private final QuizAnswerRepository answers;
    private final JsonMapper json=JsonMapper.builder().build();
    public QuizResultService(QuizAttemptRepository attempts,QuizItemRepository items,QuizAnswerRepository answers) {
        this.attempts=attempts; this.items=items; this.answers=answers;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AttemptView get(String userId,String attemptId) {
        if(userId==null || userId.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(attemptId==null || attemptId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attempt ID is required");
        var attempt=attempts.findByIdAndUserIdAndQuizSet_Material_Subject_Grade_User_Id(attemptId.trim(),userId,userId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Quiz attempt not found"));
        // ! Check completion before loading any answer key or explanation.
        if(attempt.getStatus()!=QuizAttemptStatus.SUBMITTED && attempt.getStatus()!=QuizAttemptStatus.GRADED)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Quiz not submitted");
        var set=attempt.getQuizSet();
        var questions=items.findByQuizSet_IdOrderByPositionAsc(set.getId()).stream().map(i->new ItemView(
                i.getId(),i.getPosition(),i.getType(),i.getQuestion(),parse(i.getChoices()),i.getPoints(),
                i.getTopic(),i.getExplanation(),parse(i.getAnswerKey()))).toList();
        var responses=answers.findByAttempt_Id(attempt.getId()).stream().map(a->new AnswerView(
                a.getItem().getId(),parse(a.getResponse()),a.getIsCorrect(),a.getEarned(),a.getFeedback())).toList();
        return new AttemptView(attempt.getId(),attempt.getStatus().name(),attempt.getScore(),attempt.getMaxScore(),
                attempt.getStartedAt(),attempt.getSubmittedAt(),attempt.getGradedAt(),
                new SetView(set.getId(),set.getTitle(),set.getMaterial().getId(),questions),responses);
    }
    private JsonNode parse(String value) { return value==null ? null : json.readTree(value); }
    public record ItemView(String id,int order,String type,String question,JsonNode choices,int points,
                           String topic,String explanation,JsonNode answerKey) {
        @Override public String toString() { return "ItemView[content omitted]"; }
    }
    public record AnswerView(String quizItemId,JsonNode response,Boolean isCorrect,Integer earned,String feedback) {
        @Override public String toString() { return "AnswerView[content omitted]"; }
    }
    public record SetView(String id,String title,String materialId,List<ItemView> items) {}
    public record AttemptView(String id,String status,Integer score,Integer maxScore,Instant startedAt,
                              Instant submittedAt,Instant gradedAt,SetView quizSet,List<AnswerView> answers) {}
}
