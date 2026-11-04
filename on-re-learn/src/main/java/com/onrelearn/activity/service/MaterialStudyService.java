package com.onrelearn.activity.service;

import com.onrelearn.activity.entity.MaterialStudyDay;
import com.onrelearn.activity.repository.MaterialStudyDayRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialStudyService {
    private static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Seoul");
    private final MaterialRepository materials;
    private final SubjectRepository subjects;
    private final MaterialStudyDayRepository days;

    public MaterialStudyService(MaterialRepository materials, SubjectRepository subjects,
                                MaterialStudyDayRepository days) {
        this.materials = materials;
        this.subjects = subjects;
        this.days = days;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void record(String userId, String materialId, Instant now) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (materialId == null || materialId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material ID is required");
        String id = materialId.trim();
        String subjectId = materials.findOwnedSubjectId(id, userId).orElseThrow(this::unavailable);
        // ! Share the material-delete lock so first activity and deletion cannot race.
        subjects.findOwnedForUpdate(subjectId, userId).orElseThrow(this::unavailable);
        materials.findByIdAndSubject_Grade_User_Id(id, userId).orElseThrow(this::unavailable);
        var date = now.atZone(STUDY_ZONE).toLocalDate();
        var day = days.findByUserIdAndMaterialIdAndStudyDate(userId, id, date)
            .orElseGet(() -> new MaterialStudyDay(userId, id, date, now));
        day.seenAt(now);
        days.saveAndFlush(day);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Material unavailable");
    }
}
