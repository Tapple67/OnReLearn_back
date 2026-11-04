package com.onrelearn.summary.repository;

import com.onrelearn.summary.entity.MaterialSummary;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialSummaryRepository extends JpaRepository<MaterialSummary, String> {
    Optional<MaterialSummary> findByMaterial_Id(String materialId);
}
