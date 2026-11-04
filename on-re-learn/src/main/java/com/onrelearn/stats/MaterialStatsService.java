package com.onrelearn.stats;

import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.quiz.model.QuizAttemptStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialStatsService {
    private final MaterialRepository materials;
    private final MaterialStatsRepository statistics;
    public MaterialStatsService(MaterialRepository materials, MaterialStatsRepository statistics) {
        this.materials = materials;
        this.statistics = statistics;
    }

    // * Material metadata and completed answers come from the same read snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaterialStats.Response get(String userId, String materialId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (materialId == null || materialId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material ID is required");
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId.trim(), userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Material access denied"));
        var subject = material.getSubject();
        var grade = subject.getGrade();
        var view = new MaterialStats.Material(material.getId(), material.getTitle(), material.getWeek(),
            subject.getName(), new MaterialStats.Grade(grade.getYear(), grade.getTerm()));
        var grouped = new LinkedHashMap<String, List<MaterialStatsRepository.Row>>();
        for (var row : statistics.completedRows(userId, material.getId(),
                List.of(QuizAttemptStatus.SUBMITTED, QuizAttemptStatus.GRADED))) {
            grouped.computeIfAbsent(row.getAttemptId(), ignored -> new ArrayList<>()).add(row);
        }
        var inputs = new ArrayList<MaterialStats.QuizSet>();
        for (var rows : grouped.values()) {
            var first = rows.get(0);
            var items = new ArrayList<MaterialStats.Item>();
            var answers = new ArrayList<MaterialStats.Answer>();
            for (var row : rows) {
                if (row.getItemId() == null) continue;
                items.add(new MaterialStats.Item(row.getItemId(), row.getType(), row.getTopic()));
                answers.add(new MaterialStats.Answer(row.getItemId(), row.getCorrect()));
            }
            // * Each attempt is a separate input slice; all attempts remain in the aggregate.
            inputs.add(new MaterialStats.QuizSet(first.getPurpose().name(), items,
                List.of(new MaterialStats.Attempt(first.getAttemptId(), first.getStatus().name(),
                    first.getScore(), first.getMaxScore(), first.getSubmittedAt(), answers))));
        }
        return MaterialStats.build(view, inputs);
    }
}
