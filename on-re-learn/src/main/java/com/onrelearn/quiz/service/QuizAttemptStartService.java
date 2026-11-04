package com.onrelearn.quiz.service;

import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.*;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuizAttemptStartService {
    private final QuizSetRepository sets;
    private final QuizAttemptRepository attempts;
    private final QuizItemRepository items;
    private final QuizAnswerRepository answers;
    private final SubjectRepository subjects;
    private final JsonMapper json=JsonMapper.builder().build();

    public QuizAttemptStartService(QuizSetRepository sets,QuizAttemptRepository attempts,
            QuizItemRepository items,QuizAnswerRepository answers,SubjectRepository subjects) {
        this.sets=sets; this.attempts=attempts; this.items=items; this.answers=answers; this.subjects=subjects;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Started start(String userId,String quizSetId) {
        if(userId==null || userId.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(quizSetId==null || quizSetId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Quiz set ID is required");
        String id=quizSetId.trim();
        String subjectId=sets.findOwnedSubjectId(id,userId).orElseThrow(this::unavailable);
        // ! Share the material-delete lock order. Concurrent first starts must reuse one committed attempt.
        subjects.findOwnedForUpdate(subjectId,userId).orElseThrow(this::unavailable);
        var set=sets.findByIdAndMaterial_Subject_Grade_User_Id(id,userId).orElseThrow(this::unavailable);
        var history=attempts.findByUserIdAndQuizSet_IdOrderByStartedAtDesc(userId,id);
        var completed=history.stream().filter(a -> a.getStatus()==QuizAttemptStatus.SUBMITTED || a.getStatus()==QuizAttemptStatus.GRADED).findFirst();
        // ! Preserve all existing attempts, including in-progress history when a completed attempt exists.
        if(completed.isPresent()) return new Started("already-submitted",completed.get().getId(),null,List.of());
        var attempt=history.stream().findFirst().orElseGet(() -> attempts.saveAndFlush(new QuizAttempt(userId,set)));
        var questions=items.findByQuizSet_IdOrderByPositionAsc(id).stream().map(i -> new ItemView(
                i.getId(),i.getPosition(),i.getType(),i.getQuestion(),parse(i.getChoices()),i.getTopic(),i.getPoints())).toList();
        var existing=answers.findByAttempt_Id(attempt.getId()).stream().map(a -> new AnswerView(
                a.getItem().getId(),parse(a.getResponse()),a.getUpdatedAt())).toList();
        // * Home quiz continuation reads this saved attempt directly; material study counts stay separate.
        return new Started("success",attempt.getId(),new SetView(set.getId(),set.getTitle(),questions),existing);
    }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Quiz unavailable"); }
    private JsonNode parse(String value) { return value==null ? null : json.readTree(value); }
    public record ItemView(String id,int order,String type,String question,JsonNode choices,String topic,int points) {
        @Override public String toString() { return "ItemView[content omitted]"; }
    }
    public record AnswerView(String quizItemId,JsonNode response,Instant updatedAt) {
        @Override public String toString() { return "AnswerView[content omitted]"; }
    }
    public record SetView(String id,String title,List<ItemView> items) {}
    public record Started(String status,String attemptId,SetView quizSet,List<AnswerView> existingAnswers) {}
}
