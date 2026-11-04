package com.onrelearn.quiz.service;

import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.quiz.repository.QuizSetRepository;
import java.time.Instant;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import java.util.HashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuizReadService {
    private final MaterialRepository materials;
    private final QuizSetRepository sets;

    private final QuizAttemptRepository attempts;

    public QuizReadService(MaterialRepository materials, QuizSetRepository sets, QuizAttemptRepository attempts) {
        this.attempts = attempts;
        this.materials = materials;
        this.sets = sets;
    }

    @Transactional(readOnly = true)
    public List<SetView> list(String memberId, String materialId) {
        if (memberId == null || memberId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (materialId == null || materialId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material ID is required");
        // ! An empty list is valid only after material ownership has been established.
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId.trim(), memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Material unavailable"));
        // * Input is newest first. Prefer the newest completed attempt over any in-progress one.
        // ! Read only this owner's summaries; never delete superseded attempts during a GET.
        var preferred = new HashMap<String, AttemptView>();
        for (var a : attempts.summariesForMaterial(memberId, material.getId())) {
            var prior = preferred.get(a.getQuizSetId());
            if (prior == null || (!completed(prior.status()) && completed(a.getStatus()))) {
                preferred.put(a.getQuizSetId(), new AttemptView(a.getId(), a.getStatus(), a.getScore(),
                        a.getMaxScore(), a.getStartedAt(), a.getSubmittedAt()));
            }
        }
        return sets.listForMaterial(material.getId()).stream()
                .map(s -> new SetView(s.getId(), s.getTitle(), s.getCreatedAt(), s.getItemCount(), preferred.get(s.getId()), com.onrelearn.quiz.ai.QuizPrompt.grounded(s.getPromptVersion()))).toList();
    }

    private static boolean completed(QuizAttemptStatus status) {
        return status == QuizAttemptStatus.SUBMITTED || status == QuizAttemptStatus.GRADED;
    }

    public record AttemptView(String id, QuizAttemptStatus status, Integer score, Integer maxScore,
                              Instant startedAt, Instant submittedAt) {}
    public record SetView(String id, String title, Instant createdAt, long itemCount, AttemptView latestAttempt, boolean grounded) {}
}
