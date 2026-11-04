package com.onrelearn.activity.repository;

import com.onrelearn.activity.entity.MaterialStudyDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialStudyDayRepository extends JpaRepository<MaterialStudyDay, String> {
    Optional<MaterialStudyDay> findByUserIdAndMaterialIdAndStudyDate(
        String userId, String materialId, LocalDate studyDate);

    List<MaterialStudyDay> findByUserIdAndStudyDateBetween(
        String userId, LocalDate from, LocalDate to);

    List<MaterialStudyDay> findTop20ByUserIdOrderByLastAtDescIdDesc(String userId);
}
