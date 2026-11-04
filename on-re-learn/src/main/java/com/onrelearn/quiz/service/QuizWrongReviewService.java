package com.onrelearn.quiz.service;

import com.onrelearn.quiz.ai.QuizFocus;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import com.onrelearn.quiz.repository.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuizWrongReviewService {
    private final QuizAttemptRepository attempts;
    private final QuizAnswerRepository answers;
    private final JsonMapper json = JsonMapper.builder().build();

    public QuizWrongReviewService(QuizAttemptRepository attempts, QuizAnswerRepository answers) {
        this.attempts = attempts;
        this.answers = answers;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Source load(String userId, String materialId, String sourceAttemptId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (materialId == null || materialId.isBlank() || sourceAttemptId == null || sourceAttemptId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review source and material are required");
        var attempt = attempts.findByIdAndUserIdAndQuizSet_Material_Subject_Grade_User_Id(
                sourceAttemptId.trim(), userId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review source not found"));
        // ! Authorize the material and completion before loading prior question content.
        if (!attempt.getQuizSet().getMaterial().getId().equals(materialId.trim())
                || (attempt.getStatus() != QuizAttemptStatus.SUBMITTED && attempt.getStatus() != QuizAttemptStatus.GRADED))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review source not found");
        var wrongItems = new ArrayList<QuizFocus.WrongItem>();
        var ids = new ArrayList<String>();
        var topics = json.createArrayNode();
        for (var answer : answers.findByAttempt_Id(attempt.getId())) {
            // ! Null grading is not an incorrect answer.
            if (!Boolean.FALSE.equals(answer.getIsCorrect())) continue;
            var item = answer.getItem();
            String topic = item.getTopic() == null ? "" : item.getTopic()
                    .replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
            if (topic.isEmpty()) topic = "General concept";
            wrongItems.add(new QuizFocus.WrongItem(item.getQuestion(), topic, item.getExplanation(),
                    item.getEvidence() == null ? null : json.readTree(item.getEvidence())));
            ids.add(item.getId());
            topics.add(topic);
        }
        if (wrongItems.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There are no wrong answers to review");
        return new Source(new QuizFocus(QuizFocus.Kind.WRONG_REVIEW, QuizFocusInput.topics(topics), wrongItems), ids);
    }

    // * Derived pages preserve the evidence and legacy order, including duplicates.
    public record Source(QuizFocus focus, List<String> sourceItemIds) {
        public Source { sourceItemIds = List.copyOf(sourceItemIds); }
        public List<Double> preferredPages() { return QuizEvidencePages.from(focus); }
        @Override public String toString() { return "Source[content omitted]"; }
    }
}
