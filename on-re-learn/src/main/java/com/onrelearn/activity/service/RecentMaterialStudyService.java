package com.onrelearn.activity.service;

import com.onrelearn.activity.repository.MaterialStudyDayRepository;
import com.onrelearn.material.repository.MaterialRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecentMaterialStudyService {
    private final MaterialStudyDayRepository days;
    private final MaterialRepository materials;

    public RecentMaterialStudyService(MaterialStudyDayRepository days, MaterialRepository materials) {
        this.days = days;
        this.materials = materials;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecentStudy> get(String userId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        // * The top-20 cap applies only to the read view; no study facts are pruned.
        return days.findTop20ByUserIdOrderByLastAtDescIdDesc(userId).stream().map(day -> {
            var material = materials.findByIdAndSubject_Grade_User_Id(day.getMaterialId(), userId);
            return new RecentStudy(day.getMaterialId(), material.map(m -> m.getSubject().getId()).orElse(null),
                material.map(m -> m.getTitle()).orElse("삭제된 자료"), day.getStudyDate(), day.getLastAt());
        }).toList();
    }

    public record RecentStudy(String materialId, String subjectId, String title,
                              LocalDate studyDate, Instant lastAt) {}
}
